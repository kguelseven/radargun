package org.radargun.cachewrappers;

import com.arjuna.ats.arjuna.common.arjPropertyManager;
import com.arjuna.ats.internal.arjuna.objectstore.VolatileStore;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.infinispan.Cache;
import org.infinispan.context.Flag;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.transport.Address;
import org.radargun.CacheWrapper;
import org.radargun.utils.TypedProperties;
import org.radargun.utils.Utils;

import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MINUTES;

public class InfinispanWrapper implements CacheWrapper {

   enum State {
      STOPPED,
      STARTING,
      STARTED,
      STOPPING,
      FAILED
   }
   
   static {
      // Set up transactional stores for JBoss TS
      arjPropertyManager.getCoordinatorEnvironmentBean().setCommunicationStore(VolatileStore.class.getName());
      arjPropertyManager.getObjectStoreEnvironmentBean().setObjectStoreType(VolatileStore.class.getName());
   }

   private static final String DEFAULT_CACHE_NAME = "testCache";
   private String cacheName;

   protected final Log log = LogFactory.getLog(getClass());
   private final boolean trace = log.isTraceEnabled();

   protected DefaultCacheManager cacheManager;
   protected TransactionManager tm;
   protected volatile State state = State.STOPPED;
   protected ReentrantLock stateLock = new ReentrantLock();
   protected Thread startingThread;
   protected String config;
   private volatile boolean enlistExtraXAResource;
   private Cache<Object, Object> cache;

   public void setUp(String config, boolean isLocal, int nodeIndex, TypedProperties confAttributes) throws Exception {
      this.config = config;
      try {
         if (beginStart()) {
            cacheName = getCacheName(confAttributes);
            setUpCache(confAttributes, nodeIndex);
            setUpTransactionManager();
            
            stateLock.lock();
            state = State.STARTED;
            startingThread = null;
            stateLock.unlock();

            postSetUpInternal(confAttributes);
         }
      } catch (Exception e) {
         log.error("Wrapper start failed.", e);
         if (!stateLock.isHeldByCurrentThread()) {
            stateLock.lock();
         }
         state = State.FAILED;
         startingThread = null;
         throw e;
      } finally {
         if (stateLock.isHeldByCurrentThread()) {
            stateLock.unlock();
         }
      }
   }
   
   private void setUpTransactionManager() {
      Cache<?, ?> cache = getCache(null);
      if (cache == null) return;
      tm = cache.getAdvancedCache().getTransactionManager();
      log.info("Using transaction manager: " + tm);
   }

   protected String getConfigFile(TypedProperties confAttributes) {
      return confAttributes.containsKey("file") ? confAttributes.getProperty("file") : config;
   }
   
   protected String getCacheName(TypedProperties confAttributes) {
      return confAttributes.containsKey("cache") ? confAttributes.getProperty("cache") : DEFAULT_CACHE_NAME;
   }
   
   protected void setUpCache(TypedProperties confAttributes, int nodeIndex) throws Exception {     
      String configFile = getConfigFile(confAttributes);
      String cacheName = getCacheName(confAttributes);
      
      log.trace("Using config file: " + configFile + " and cache name: " + cacheName);

      cacheManager = createCacheManager(configFile);
      String cacheNames = cacheManager.getDefinedCacheNames();
      if (!cacheNames.contains(cacheName))
         throw new IllegalStateException("The requested cache(" + cacheName + ") is not defined. Defined cache " +
                                               "names are " + cacheNames);
      cache = cacheManager.getCache(cacheName);
   }

   protected DefaultCacheManager createCacheManager(String configFile) throws IOException {
      return new DefaultCacheManager(configFile);
   }

   protected void postSetUpInternal(TypedProperties confAttributes) throws Exception {
      log.debug("Loading JGroups from: " + org.jgroups.Version.class.getProtectionDomain().getCodeSource().getLocation());
      log.info("JGroups version: " + org.jgroups.Version.printDescription());
      log.info("Using config attributes: " + confAttributes);
      waitForRehash(confAttributes);
   }

   protected void waitForRehash(TypedProperties confAttributes) throws InterruptedException {
      blockForRehashing(getCache(null));
      injectEvenConsistentHash(getCache(null), confAttributes);
   }
   
   public void tearDown() throws Exception {     
      try {
         if (beginStop(false)) {
            List<Address> addressList = cacheManager.getMembers();
            cacheManager.stop();         
            log.info("Stopped, previous view is " + addressList);
                    
            stateLock.lock();
            state = State.STOPPED;
         }
      } catch (Exception e) {
         log.error("Wrapper tear down failed.");
         if (!stateLock.isHeldByCurrentThread()) {
            stateLock.lock();
         }
         state = State.FAILED;
         throw e;
      } finally {
         if (stateLock.isHeldByCurrentThread()) {
            stateLock.unlock();
         }
      }
   }

   protected boolean beginStart() throws InterruptedException {
      try {
         stateLock.lock();
         while (state == State.STOPPING) {
            stateLock.unlock();
            log.info("Waiting for the wrapper to stop");
            Thread.sleep(1000);
            stateLock.lock();
         }
         if (state == State.FAILED){
            log.info("Cannot start, previous attempt failed");
         } else if (state == State.STARTING) {
            log.info("Wrapper already starting");
         } else if (state == State.STARTED) {
            log.info("Wrapper already started");
         } else if (state == State.STOPPED) {
            state = State.STARTING;
            startingThread = Thread.currentThread();
            return true;
         }
         return false;
      } finally {
         if (stateLock.isHeldByCurrentThread()) {
            stateLock.unlock();
         }
      }
   }

   protected boolean beginStop(boolean interrupt) throws InterruptedException {
      try {
         stateLock.lock();
         if (interrupt && startingThread != null) {
            log.info("Interrupting the starting thread");
            startingThread.interrupt();
         }
         while (state == State.STARTING) {
            stateLock.unlock();
            log.info("Waiting for the wrapper to start");
            Thread.sleep(1000);
            stateLock.lock();
         }
         if (state == State.FAILED) {
            log.info("Cannot stop, previous attempt failed.");
         } else if (state == State.STOPPING) {
            log.warn("Wrapper already stopping");
         } else if (state == State.STOPPED) {
            log.warn("Wrapper already stopped");
         } else if (state == State.STARTED) {
            state = State.STOPPING;
            return true;
         }
         return false;
      } finally {
         if (stateLock.isHeldByCurrentThread()) {
            stateLock.unlock();
         }
      }
   }

   @Override
   public boolean isRunning() {
      try {
         stateLock.lock();
         return state == State.STARTED;
      } finally {
         if (stateLock.isHeldByCurrentThread()) {
            stateLock.unlock();
         }
      }
   }

   @Override
   public void put(String bucket, Object key, Object value) throws Exception {
      if (trace) log.trace("PUT key=" + key);
      getCache(bucket).put(key, value);
   }
   
   @Override
   public Object get(String bucket, Object key) throws Exception {
      if (trace) log.trace("GET key=" + key);
      return getCache(bucket).get(key);
   }
   
   @Override
   public Object remove(String bucket, Object key) throws Exception {
      if (trace) log.trace("REMOVE key=" + key);
      return getCache(bucket).remove(key);
   }

   @Override
   public void empty() throws Exception {
      RpcManager rpcManager = getCache(null).getAdvancedCache().getRpcManager();
      int clusterSize = 0;
      if (rpcManager != null) {
         clusterSize = rpcManager.getTransport().getMembers().size();
      }
      //use keySet().size() rather than size directly as cache.size might not be reliable
      log.info("Cache size before clear (cluster size= " + clusterSize + ")" + getCache(null).keySet().size());

      getCache(null).getAdvancedCache().withFlags(Flag.CACHE_MODE_LOCAL).clear();
      log.info("Cache size after clear: " + getCache(null).keySet().size());
   }

   public int getNumMembers() {
      ComponentRegistry componentRegistry = getCache(null).getAdvancedCache().getComponentRegistry();
      if (componentRegistry.getStatus().startingUp()) {
         log.trace("We're in the process of starting up.");
      }
      if (cacheManager.getMembers() != null) {
         log.trace("Members are: " + cacheManager.getMembers());
      }
      return cacheManager.getMembers() == null ? 0 : cacheManager.getMembers().size();
   }

   public String getInfo() {
      //Important: don't change this string without validating the ./dist.sh as it relies on its format!!
      return "Running : " + getCache(null).getVersion() +  ", config:" + config + ", cacheName:" + getCache(null).getName();
   }

   public Object getReplicatedData(String bucket, String key) throws Exception {
      return get(bucket, key);
   }

   public void startTransaction() {
      assertTm();
      try {
         tm.begin();
         Transaction transaction = tm.getTransaction();
         if (enlistExtraXAResource) {
            transaction.enlistResource(new DummyXAResource());
         }
      }
      catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   public void endTransaction(boolean successful) {
      assertTm();
      try {
         if (successful)
            tm.commit();
         else
            tm.rollback();
      }
      catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   @Override
   public int getLocalSize() {
      return getCache(null).size();
   }
   
   @Override
   public int getTotalSize() {
      return -1; // Infinispan does not provide this directly, JMX stats would have to be summed
   }

   protected void blockForRehashing(Cache<Object, Object> aCache) throws InterruptedException {
      // should we be blocking until all rehashing, etc. has finished?
      long gracePeriod = MINUTES.toMillis(15);
      long giveup = System.currentTimeMillis() + gracePeriod;
      if (aCache.getConfiguration().getCacheMode().isDistributed()) {
         while (!aCache.getAdvancedCache().getDistributionManager().isJoinComplete() && System.currentTimeMillis() < giveup)
            Thread.sleep(200);
      }

      if (aCache.getConfiguration().getCacheMode().isDistributed() && !aCache.getAdvancedCache().getDistributionManager().isJoinComplete())
         throw new RuntimeException("Caches haven't discovered and joined the cluster even after " + Utils.prettyPrintMillis(gracePeriod));
   }

   protected void injectEvenConsistentHash(Cache<Object, Object> aCache, TypedProperties confAttributes) {
      if (aCache.getConfiguration().getCacheMode().isDistributed()) {
         ConsistentHash ch = aCache.getAdvancedCache().getDistributionManager().getConsistentHash();
         if (ch instanceof EvenSpreadingConsistentHash) {
            int threadsPerNode = confAttributes.getIntProperty("threadsPerNode", -1);
            if (threadsPerNode < 0) throw new IllegalStateException("When EvenSpreadingConsistentHash is used threadsPerNode must also be set.");
            int keysPerThread = confAttributes.getIntProperty("keysPerThread", -1);
            if (keysPerThread < 0) throw new IllegalStateException("When EvenSpreadingConsistentHash is used must also be set.");
            ((EvenSpreadingConsistentHash)ch).init(threadsPerNode, keysPerThread);
            log.info("Using an even consistent hash!");
         }

      }
   }

   public Cache<Object, Object> getCache(String bucket) {
      return cache;
   }

   private void assertTm() {
      if (tm == null) throw new RuntimeException("No configured TM!");
   }

   public void setEnlistExtraXAResource(boolean enlistExtraXAResource) {
      this.enlistExtraXAResource = enlistExtraXAResource;
   }
   
   public String getCacheName() {
      return cacheName;
   }

}

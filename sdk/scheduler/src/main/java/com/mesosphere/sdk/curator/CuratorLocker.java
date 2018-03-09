package com.mesosphere.sdk.curator;

import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.mesosphere.sdk.framework.ExitCode;
import com.mesosphere.sdk.scheduler.SchedulerUtils;
import com.mesosphere.sdk.storage.PersisterUtils;

/**
 * Gets an exclusive lock on service-specific ZK node to ensure two schedulers aren't running simultaneously for the
 * same service.
 */
public class CuratorLocker {
    private static final Logger LOGGER = LoggerFactory.getLogger(CuratorUtils.class);

    private static final int LOCK_ATTEMPTS = 3;
    static final String LOCK_PATH_NAME = "lock";

    private static boolean enabled = true;
    private static final Object INSTANCE_LOCK = new Object();
    private static CuratorLocker instance = null;
    private static final Thread SHUTDOWN_HOOK = new Thread(() -> {
        if (instance != null) {
            LOGGER.info("Shutdown initiated, releasing curator lock");
            instance.unlockInternal();
        }
    });

    private final String serviceName;
    private final String zookeeperHostPort;

    private CuratorFramework curatorClient;
    private InterProcessSemaphoreMutex curatorMutex;

    /**
     * Locks curator. This should only be called once per process. Throws if called a second time.
     *
     * @param serviceName the name of the service to be locked
     * @param zookeeperHostPort the connection string for the ZK instance, e.g. {@code master.mesos:2181}
     */
    public static void lock(String serviceName, String zookeeperHostPort) {
        synchronized (INSTANCE_LOCK) {
            if (!enabled) {
                return;
            }
            if (instance != null) {
                throw new IllegalStateException("Already locked");
            }
            instance = new CuratorLocker(serviceName, zookeeperHostPort);
            instance.lockInternal();

            Runtime.getRuntime().addShutdownHook(SHUTDOWN_HOOK);
        }
    }

    /**
     * Allows disabling locking for unit tests of things that internally try to acquire a curator lock.
     */
    @VisibleForTesting
    public static void setEnabledForTests(boolean enabled) {
        CuratorLocker.enabled = enabled;
    }

    @VisibleForTesting
    static void unlock() {
        synchronized (INSTANCE_LOCK) {
            if (instance == null) {
                return; // No-op
            }
            instance.unlockInternal();
            instance = null;

            Runtime.getRuntime().removeShutdownHook(SHUTDOWN_HOOK);
        }
    }

    /**
     * @param serviceName the name of the service to be locked
     * @param zookeeperHostPort the connection string for the ZK instance, e.g. {@code master.mesos:2181}
     */
    @VisibleForTesting
    CuratorLocker(String serviceName, String zookeeperHostPort) {
        this.serviceName = serviceName;
        this.zookeeperHostPort = zookeeperHostPort;
    }

    /**
     * Gets an exclusive lock on service-specific ZK node to ensure two schedulers aren't running simultaneously for the
     * same service.
     */
    @VisibleForTesting
    void lockInternal() {
        if (curatorClient != null) {
            throw new IllegalStateException("Already locked");
        }
        curatorClient = CuratorFrameworkFactory.newClient(zookeeperHostPort, CuratorUtils.getDefaultRetry());
        curatorClient.start();

        final String lockPath = PersisterUtils.join(CuratorUtils.getServiceRootPath(serviceName), LOCK_PATH_NAME);
        InterProcessSemaphoreMutex curatorMutex = new InterProcessSemaphoreMutex(curatorClient, lockPath);

        LOGGER.info("Acquiring ZK lock on {}...", lockPath);
        final String failureLogMsg = String.format("Failed to acquire ZK lock on %s. " +
                "Duplicate service named '%s', or recently restarted instance of '%s'?",
                lockPath, serviceName, serviceName);
        try {
            // Start at 1 for pretty display of "1/3" through "3/3":
            for (int attempt = 1; attempt < LOCK_ATTEMPTS + 1; ++attempt) {
                if (curatorMutex.acquire(10, getWaitTimeUnit())) {
                    LOGGER.info("{}/{} Lock acquired.", attempt, LOCK_ATTEMPTS);
                    this.curatorMutex = curatorMutex;
                    return;
                }
                if (attempt < LOCK_ATTEMPTS) {
                    LOGGER.error("{}/{} {} Retrying lock...", attempt, LOCK_ATTEMPTS, failureLogMsg);
                }
            }
            LOGGER.error(failureLogMsg + " Restarting scheduler process to try again.");
        } catch (Exception ex) {
            LOGGER.error(String.format("Error acquiring ZK lock on path: %s", lockPath), ex);
        }
        curatorClient = null;
        exit();
    }

    /**
     * Releases the lock previously obtained via {@link #lock()}.
     */
    @VisibleForTesting
    void unlockInternal() {
        if (curatorClient == null) {
            throw new IllegalStateException("Already unlocked");
        }
        try {
            curatorMutex.release();
        } catch (Exception ex) {
            LOGGER.error("Error releasing ZK lock.", ex);
        }
        curatorClient.close();
        curatorMutex = null;
        curatorClient = null;
    }

    /**
     * Broken out into a separate function to allow overrides in tests.
     */
    @VisibleForTesting
    protected TimeUnit getWaitTimeUnit() {
        return TimeUnit.SECONDS;
    }

    /**
     * Broken out into a separate function to allow overrides in tests.
     */
    @VisibleForTesting
    protected void exit() {
        SchedulerUtils.hardExit(ExitCode.LOCK_UNAVAILABLE);
    }
}

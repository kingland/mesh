package com.gentics.mesh.core.verticle.handler;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.gentics.mesh.context.InternalActionContext;
import com.gentics.mesh.etc.config.MeshOptions;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.ILock;

import dagger.Lazy;

@Singleton
public class WriteLockImpl implements WriteLock {

	private final Semaphore localLock = new Semaphore(1);
	private ILock clusterLock;
	private final MeshOptions options;
	private final Lazy<HazelcastInstance> hazelcast;
	private final boolean isClustered;

	@Inject
	public WriteLockImpl(MeshOptions options, Lazy<HazelcastInstance> hazelcast) {
		this.options = options;
		this.hazelcast = hazelcast;
		this.isClustered = options.getClusterOptions().isEnabled();
	}

	@Override
	public void close() {
		if (isClustered) {
			if (clusterLock != null) {
				clusterLock.unlock();
			}
		} else {
			localLock.release();
		}
	}

	/**
	 * Locks writes. Use this to prevent concurrent write transactions.
	 */
	@Override
	public WriteLock lock(InternalActionContext ac) {
		if (ac != null && ac.isSkipWriteLock()) {
			return this;
		} else {
			boolean syncWrites = options.getStorageOptions().isSynchronizeWrites();
			if (syncWrites) {
				long timeout = options.getStorageOptions().getSynchronizeWritesTimeout();
				if (isClustered) {
					try {
						if (clusterLock == null) {
							HazelcastInstance hz = hazelcast.get();
							if (hz != null) {
								this.clusterLock = hz.getLock(WRITE_LOCK_KEY);
							}
						}
						boolean isTimeout = !clusterLock.tryLock(timeout, TimeUnit.MILLISECONDS);
						if (isTimeout) {
							throw new RuntimeException("Got timeout while waiting for write lock.");
						}
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
				} else {
					try {
						boolean isTimeout = !localLock.tryAcquire(timeout, TimeUnit.MILLISECONDS);
						if (isTimeout) {
							throw new RuntimeException("Got timeout while waiting for write lock.");
						}
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
				}
			}
			return this;
		}
	}

}

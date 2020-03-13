package com.gentics.mesh.core.verticle.handler;

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

	private ILock lock;
	private final MeshOptions options;
	private final Lazy<HazelcastInstance> hazelcast;

	@Inject
	public WriteLockImpl(MeshOptions options, Lazy<HazelcastInstance> hazelcast) {
		this.options = options;
		this.hazelcast = hazelcast;
	}

	@Override
	public void close() {
		if (lock != null) {
			lock.unlock();
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
				try {
					if (lock == null) {
						HazelcastInstance hz = hazelcast.get();
						if (hz != null) {
							this.lock = hz.getLock(WRITE_LOCK_KEY);
						}
					}
					boolean isTimeout = !lock.tryLock(240, TimeUnit.SECONDS);
					if (isTimeout) {
						throw new RuntimeException("Got timeout while waiting for write lock.");
					}
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
			return this;
		}
	}

}

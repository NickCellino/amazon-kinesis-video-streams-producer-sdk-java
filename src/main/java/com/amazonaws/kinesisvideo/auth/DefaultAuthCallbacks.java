package com.amazonaws.kinesisvideo.auth;

import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import com.amazonaws.kinesisvideo.common.preconditions.Preconditions;
import com.amazonaws.kinesisvideo.producer.AuthCallbacks;
import com.amazonaws.kinesisvideo.producer.AuthInfo;
import com.amazonaws.kinesisvideo.producer.AuthInfoType;
import com.amazonaws.kinesisvideo.producer.Time;

import javax.annotation.Nullable;
import javax.annotation.Nonnull;

import java.io.*;
import java.util.concurrent.*;

/**
 * Default AuthCallbacks implementation based on the credentials provider
 */
public class DefaultAuthCallbacks implements AuthCallbacks {
    /**
     * Default timeout for the credentials
     */
    private static final int CREDENTIALS_UPDATE_TIMEOUT_MILLIS = 10000;

    /**
     * A sentinel value indicating the credentials never expire
     */
    public static final long CREDENTIALS_NEVER_EXPIRE = Long.MAX_VALUE;

    /**
     * Stored credentials provider
     */
    private final KinesisVideoCredentialsProvider credentialsProvider;

    /**
     * Executor service to use as the calls can come in on the main thread.
     */
    private final ScheduledExecutorService executor;

    /**
     * Used for logging
     */
    private final Logger logger;

    /**
     * Stores the serialized credentials
     */
    private byte[] serializedCredentials;

    /**
     * Expiration of the credentials
     */
    private long expiration;

    public DefaultAuthCallbacks(@Nonnull KinesisVideoCredentialsProvider credentialsProvider,
                                @Nonnull final ScheduledExecutorService executor,
                                @Nonnull Logger logger) {
        this.credentialsProvider = Preconditions.checkNotNull(credentialsProvider);
        this.executor = Preconditions.checkNotNull(executor);
        this.logger = Preconditions.checkNotNull(logger);
    }

    @Nullable
    @Override
    public AuthInfo getDeviceCertificate() {
        throw new RuntimeException("Certificate integration is not implemented");
    }

    @Nullable
    @Override
    public AuthInfo getSecurityToken() {
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                // Get the updated credentials and serialize it

                final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                try {
                    final KinesisVideoCredentials credentials = credentialsProvider.getUpdatedCredentials();
                    expiration = credentials.getExpiration().getTime() * Time.HUNDREDS_OF_NANOS_IN_A_MILLISECOND;

                    final ObjectOutput outputStream = new ObjectOutputStream(byteArrayOutputStream);
                    outputStream.writeObject(credentials);
                    outputStream.flush();
                    serializedCredentials = byteArrayOutputStream.toByteArray();
                    outputStream.close();
                } catch (final IOException e) {
                    // return null
                    serializedCredentials = null;
                    expiration = 0;
                    logger.log(Level.getLevel("EXCEPTION"), e.getClass().getSimpleName() + ": Exception was thrown trying to get updated credentials" + e.getMessage(), e);
                } catch (final KinesisVideoException e) {
                    // return null
                    serializedCredentials = null;
                    expiration = 0;
                    logger.log(Level.getLevel("EXCEPTION"), e.getClass().getSimpleName() + ": Exception was thrown trying to get updated credentials" + e.getMessage(), e);
                } finally {
                    try {
                        byteArrayOutputStream.close();
                    }
                    catch(final IOException e) {
                        // Do nothing
                        logger.log(Level.getLevel("EXCEPTION"), e.getClass().getSimpleName() + ": Closing the byte array stream threw an exception" + e.getMessage(), e);
                    }
                }
            }
        };

        final ScheduledFuture<?> future = executor.schedule(task, 0, TimeUnit.NANOSECONDS);

        // Await for the future to complete
        try {
            future.get(CREDENTIALS_UPDATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            logger.log(Level.getLevel("EXCEPTION"), e.getClass().getSimpleName() + ": Awaiting for the credentials update threw an exception" + e.getMessage(), e);
        } catch (final ExecutionException e) {
            logger.log(Level.getLevel("EXCEPTION"), e.getClass().getSimpleName() + ": Awaiting for the credentials update threw an exception" + e.getMessage(), e);
        } catch (final TimeoutException e) {
            logger.log(Level.getLevel("EXCEPTION"), e.getClass().getSimpleName() + ": Awaiting for the credentials update threw an exception" + e.getMessage(), e);
        }

        return new AuthInfo(
                AuthInfoType.SECURITY_TOKEN,
                serializedCredentials,
                expiration);
    }

    @Nullable
    @Override
    public String getDeviceFingerprint() {
        throw new RuntimeException("Provisioning is not implemented");
    }
}

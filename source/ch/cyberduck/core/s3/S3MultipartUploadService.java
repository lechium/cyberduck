package ch.cyberduck.core.s3;

/*
 * Copyright (c) 2002-2013 David Kocher. All rights reserved.
 * http://cyberduck.ch/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Bug fixes, suggestions and comments should be sent to feedback@cyberduck.ch
 */

import ch.cyberduck.core.DefaultIOExceptionMappingService;
import ch.cyberduck.core.Local;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.PathContainerService;
import ch.cyberduck.core.Preferences;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.exception.ConnectionCanceledException;
import ch.cyberduck.core.features.Upload;
import ch.cyberduck.core.http.ResponseOutputStream;
import ch.cyberduck.core.io.BandwidthThrottle;
import ch.cyberduck.core.io.StreamCopier;
import ch.cyberduck.core.io.StreamListener;
import ch.cyberduck.core.io.ThrottledOutputStream;
import ch.cyberduck.core.threading.ThreadPool;
import ch.cyberduck.core.transfer.TransferStatus;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.jets3t.service.ServiceException;
import org.jets3t.service.model.MultipartCompleted;
import org.jets3t.service.model.MultipartPart;
import org.jets3t.service.model.MultipartUpload;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.model.StorageObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * @version $Id$
 */
public class S3MultipartUploadService implements Upload<String> {
    private static final Logger log = Logger.getLogger(S3MultipartUploadService.class);

    /**
     * The maximum allowed parts in a multipart upload.
     */
    public static final int MAXIMUM_UPLOAD_PARTS = 10000;


    private S3Session session;

    private PathContainerService containerService
            = new PathContainerService();

    private S3MultipartService multipartService;

    /**
     * At any point, at most <tt>nThreads</tt> threads will be active processing tasks.
     */
    private ThreadPool pool = new ThreadPool(
            Preferences.instance().getInteger("s3.upload.multipart.concurency"), "multipart");

    /**
     * A split smaller than 5M is not allowed
     */
    private Long partsize;

    public S3MultipartUploadService(final S3Session session) {
        this(session, Preferences.instance().getLong("s3.upload.multipart.size"));
    }

    public S3MultipartUploadService(final S3Session session, final Long partsize) {
        this.session = session;
        this.multipartService = new S3MultipartService(session);
        this.partsize = partsize;
    }

    @Override
    public String upload(final Path file, final Local local, final BandwidthThrottle throttle, final StreamListener listener,
                         final TransferStatus status) throws BackgroundException {
        try {
            MultipartUpload multipart = null;
            if(status.isAppend()) {
                multipart = multipartService.find(file);
            }
            if(null == multipart) {
                if(log.isInfoEnabled()) {
                    log.info("No pending multipart upload found");
                }
                final S3Object object = new S3WriteFeature(session).createObjectDetails(file);
                // ID for the initiated multipart upload.
                multipart = session.getClient().multipartStartUpload(
                        containerService.getContainer(file).getName(), object);
                if(log.isDebugEnabled()) {
                    log.debug(String.format("Multipart upload started for %s with ID %s",
                            multipart.getObjectKey(), multipart.getUploadId()));
                }
            }
            final List<MultipartPart> completed = new ArrayList<MultipartPart>();
            if(status.isAppend()) {
                completed.addAll(multipartService.list(multipart));
            }
            try {
                final List<Future<MultipartPart>> parts = new ArrayList<Future<MultipartPart>>();
                long remaining = status.getLength();
                long offset = 0;
                for(int partNumber = 1; remaining > 0; partNumber++) {
                    boolean skip = false;
                    if(status.isAppend()) {
                        if(log.isInfoEnabled()) {
                            log.info(String.format("Determine if part number %d can be skipped", partNumber));
                        }
                        for(MultipartPart c : completed) {
                            if(c.getPartNumber().equals(partNumber)) {
                                if(log.isInfoEnabled()) {
                                    log.info(String.format("Skip completed part number %d", partNumber));
                                }
                                skip = true;
                                break;
                            }
                        }
                    }
                    // Last part can be less than 5 MB. Adjust part size.
                    final Long length = Math.min(Math.max((status.getLength() / MAXIMUM_UPLOAD_PARTS), partsize), remaining);
                    if(!skip) {
                        // Submit to queue
                        final Future<MultipartPart> multipartPartFuture = this.submitPart(file, local, throttle, listener,
                                status, multipart, partNumber, offset, length);
                        parts.add(multipartPartFuture);
                    }
                    remaining -= length;
                    offset += length;
                }
                for(Future<MultipartPart> future : parts) {
                    try {
                        completed.add(future.get());
                    }
                    catch(InterruptedException e) {
                        log.error("Part upload failed with interrupt failure");
                        throw new ConnectionCanceledException(e);
                    }
                    catch(ExecutionException e) {
                        log.warn(String.format("Part upload failed with execution failure %s", e.getMessage()));
                        if(e.getCause() instanceof BackgroundException) {
                            throw (BackgroundException) e.getCause();
                        }
                        throw new BackgroundException(e);
                    }
                }
                // Combining all the given parts into the final object. Processing of a Complete Multipart Upload request
                // could take several minutes to complete. Because a request could fail after the initial 200 OK response
                // has been sent, it is important that you check the response body to determine whether the request succeeded.
                final MultipartCompleted complete = session.getClient().multipartCompleteUpload(multipart, completed);
                if(log.isDebugEnabled()) {
                    log.debug(String.format("Completed multipart upload for %s with checksum %s",
                            complete.getObjectKey(), complete.getEtag()));
                }
                return complete.getEtag();
            }
            finally {
                // Cancel future tasks
                pool.shutdown();
            }
        }
        catch(ServiceException e) {
            throw new ServiceExceptionMappingService().map("Upload failed", e, file);
        }
    }

    private Future<MultipartPart> submitPart(final Path file,
                                             final Local local,
                                             final BandwidthThrottle throttle, final StreamListener listener,
                                             final TransferStatus status, final MultipartUpload multipart,
                                             final int partNumber, final long offset, final long length) throws BackgroundException {
        if(log.isInfoEnabled()) {
            log.info(String.format("Submit part %d of %s to queue with offset %d and length %d", partNumber, file, offset, length));
        }
        return pool.execute(new Callable<MultipartPart>() {
            @Override
            public MultipartPart call() throws BackgroundException {
                final Map<String, String> requestParameters = new HashMap<String, String>();
                requestParameters.put("uploadId", multipart.getUploadId());
                requestParameters.put("partNumber", String.valueOf(partNumber));

                InputStream in = null;
                ResponseOutputStream<StorageObject> out = null;
                try {
                    in = local.getInputStream();
                    out = new S3WriteFeature(session).write(file, new StorageObject(containerService.getKey(file)), length, requestParameters);
                    new StreamCopier(status, status).transfer(in, offset, new ThrottledOutputStream(out, throttle), listener, length);
                    final StorageObject part = out.getResponse();
                    if(log.isInfoEnabled()) {
                        log.info(String.format("Received response for part number %d", partNumber));
                    }
                    // Populate part with response data that is accessible via the object's metadata
                    return new MultipartPart(partNumber, part.getLastModifiedDate(),
                            part.getETag(), part.getContentLength());
                }
                catch(IOException e) {
                    throw new DefaultIOExceptionMappingService().map(e);
                }
                finally {
                    IOUtils.closeQuietly(in);
                    IOUtils.closeQuietly(out);
                }
            }
        });
    }
}

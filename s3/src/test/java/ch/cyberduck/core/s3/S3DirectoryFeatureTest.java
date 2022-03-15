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

import ch.cyberduck.core.*;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.exception.InteroperabilityException;
import ch.cyberduck.core.features.Delete;
import ch.cyberduck.core.features.Location;
import ch.cyberduck.core.shared.DefaultFindFeature;
import ch.cyberduck.core.shared.DefaultHomeFinderService;
import ch.cyberduck.core.threading.BackgroundExceptionCallable;
import ch.cyberduck.core.transfer.TransferStatus;
import ch.cyberduck.test.IntegrationTest;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

@Category(IntegrationTest.class)
public class S3DirectoryFeatureTest extends AbstractS3Test {

    @Test
    public void testCreateBucket() throws Exception {
        final S3DirectoryFeature feature = new S3DirectoryFeature(session, new S3WriteFeature(session));
        for(Location.Name region : session.getHost().getProtocol().getRegions()) {
            switch(region.getIdentifier()) {
                case "me-south-1":
                case "ap-east-1":
                    // Not enabled for account
                    break;
                default:
                    final Path test = new Path(new DefaultHomeFinderService(session).find(), new AsciiRandomStringService().random(), EnumSet.of(Path.Type.directory, Path.Type.volume));
                    assertTrue(feature.isSupported(test.getParent(), test.getName()));
                    test.attributes().setRegion(region.getIdentifier());
                    feature.mkdir(test, new TransferStatus().withRegion(region.getIdentifier()));
                    assertTrue(new S3FindFeature(session).find(test));
                    assertEquals(region.getIdentifier(), new S3LocationFeature(session).getLocation(test).getIdentifier());
                    new S3DefaultDeleteFeature(session).delete(Collections.singletonList(test), new DisabledLoginCallback(), new Delete.DisabledCallback());
            }
        }
    }

    @Test(expected = InteroperabilityException.class)
    public void testCreateBucketInvalidName() throws Exception {
        final S3DirectoryFeature feature = new S3DirectoryFeature(session, new S3WriteFeature(session));
        final Path test = new Path(new DefaultHomeFinderService(session).find(), "untitled folder", EnumSet.of(Path.Type.directory, Path.Type.volume));
        assertFalse(new S3DirectoryFeature(session, new S3WriteFeature(session)).isSupported(test.getParent(), test.getName()));
        assertTrue(new S3DirectoryFeature(virtualhost, new S3WriteFeature(session)).isSupported(test.getParent(), test.getName()));
        final S3LocationFeature.S3Region region = new S3LocationFeature.S3Region("eu-west-2");
        test.attributes().setRegion(region.getIdentifier());
        new S3DirectoryFeature(session, new S3WriteFeature(session)).mkdir(test, new TransferStatus().withRegion(region.getIdentifier()));
        assertTrue(new S3FindFeature(session).find(test));
        new S3DefaultDeleteFeature(session).delete(Collections.singletonList(test), new DisabledLoginCallback(), new Delete.DisabledCallback());
    }

    @Test
    public void testCreatePlaceholder() throws Exception {
        final AtomicBoolean b = new AtomicBoolean();
        final String name = new AlphanumericRandomStringService().random();
        session.withListener(new TranscriptListener() {
            @Override
            public void log(final Type request, final String message) {
                switch(request) {
                    case request:
                        if(("PUT /" + name + "/ HTTP/1.1").equals(message)) {
                            b.set(true);
                        }
                }
            }
        });
        final Path container = new Path("test-eu-central-1-cyberduck", EnumSet.of(Path.Type.directory, Path.Type.volume));
        final Path test = new S3DirectoryFeature(session, new S3WriteFeature(session)).mkdir(new Path(container, name, EnumSet.of(Path.Type.directory)), new TransferStatus());
        assertTrue(test.getType().contains(Path.Type.placeholder));
        assertTrue(b.get());
        assertTrue(new S3FindFeature(session).find(test));
        assertTrue(new S3ObjectListService(session).list(container, new DisabledListProgressListener()).contains(test));
        assertTrue(new S3ObjectListService(session).list(test, new DisabledListProgressListener()).isEmpty());
        assertTrue(new S3VersionedObjectListService(session).list(container, new DisabledListProgressListener()).contains(test));
        assertTrue(new S3VersionedObjectListService(session).list(test, new DisabledListProgressListener()).isEmpty());
        assertTrue(new DefaultFindFeature(session).find(test));
        new S3DefaultDeleteFeature(session).delete(Collections.singletonList(test), new DisabledLoginCallback(), new Delete.DisabledCallback());
    }

    @Test
    public void testCreatePlaceholderMinio() throws Exception {
        final Host host = new Host(new S3Protocol(), "play.minio.io", 9000, new Credentials(
            "Q3AM3UQ867SPQQA43P2F", "zuf+tfteSlswRu7BJ86wekitnifILbZam1KYY3TG"
        ));
        final S3Session session = new S3Session(host);
        final LoginConnectionService login = new LoginConnectionService(new DisabledLoginCallback(), new DisabledHostKeyCallback(),
            new DisabledPasswordStore(), new DisabledProgressListener());
        login.check(session, new DisabledCancelCallback());
        final String name = String.format("%s %s", new AlphanumericRandomStringService().random(), new AlphanumericRandomStringService().random());
        final Path bucket = new S3PathStyleFallbackAdapter<>(session, new BackgroundExceptionCallable<Path>() {
            @Override
            public Path call() throws BackgroundException {
                return new S3DirectoryFeature(session, new S3WriteFeature(session)).mkdir(
                    new Path(new DefaultHomeFinderService(session).find(), new AsciiRandomStringService().random(), EnumSet.of(Path.Type.directory, Path.Type.volume)), new TransferStatus());
            }
        }).call();
        final Path test = new S3PathStyleFallbackAdapter<>(session, new BackgroundExceptionCallable<Path>() {
            @Override
            public Path call() throws BackgroundException {
                return new S3DirectoryFeature(session, new S3WriteFeature(session)).mkdir(new Path(bucket, name, EnumSet.of(Path.Type.directory)), new TransferStatus());
            }
        }).call();
        assertTrue(test.getType().contains(Path.Type.placeholder));
        assertTrue(new S3FindFeature(session).find(test));
        assertNotNull(new S3AttributesFinderFeature(session).find(test));
        assertTrue(new S3ObjectListService(session).list(bucket, new DisabledListProgressListener()).contains(test));
        assertTrue(new S3ObjectListService(session).list(test, new DisabledListProgressListener()).isEmpty());
        assertTrue(new S3VersionedObjectListService(session).list(test, new DisabledListProgressListener()).isEmpty());
        assertTrue(new DefaultFindFeature(session).find(test));
        new S3DefaultDeleteFeature(session).delete(Arrays.asList(test, bucket), new DisabledLoginCallback(), new Delete.DisabledCallback());
    }

    @Test
    public void testCreatePlaceholderVersioningDelete() throws Exception {
        final Path bucket = new Path("versioning-test-us-east-1-cyberduck", EnumSet.of(Path.Type.directory, Path.Type.volume));
        final Path test = new S3DirectoryFeature(session, new S3WriteFeature(session)).mkdir(new Path(bucket, new AlphanumericRandomStringService().random(), EnumSet.of(Path.Type.directory)), new TransferStatus());
        assertTrue(test.getType().contains(Path.Type.placeholder));
        assertTrue(new S3FindFeature(session).find(test));
        assertTrue(new S3VersionedObjectListService(session).list(bucket, new DisabledListProgressListener()).contains(test));
        new S3DefaultDeleteFeature(session).delete(Collections.singletonList(test), new DisabledLoginCallback(), new Delete.DisabledCallback());
        assertFalse(new S3VersionedObjectListService(session).list(bucket, new DisabledListProgressListener()).contains(test));
        assertFalse(new DefaultFindFeature(session).find(test));
        assertFalse(new S3FindFeature(session).find(test));
    }

    @Test
    public void testCreatePlaceholderVersioningDeleteWithMarker() throws Exception {
        final Path bucket = new Path("versioning-test-us-east-1-cyberduck", EnumSet.of(Path.Type.directory, Path.Type.volume));
        final Path directory = new S3DirectoryFeature(session, new S3WriteFeature(session)).mkdir(new Path(bucket, new AlphanumericRandomStringService().random(), EnumSet.of(Path.Type.directory)), new TransferStatus());
        final Path test = new S3DirectoryFeature(session, new S3WriteFeature(session)).mkdir(new Path(directory, new AlphanumericRandomStringService().random(), EnumSet.of(Path.Type.directory)), new TransferStatus());
        assertTrue(test.getType().contains(Path.Type.placeholder));
        assertTrue(new S3FindFeature(session).find(test));
        assertTrue(new S3VersionedObjectListService(session).list(directory, new DisabledListProgressListener()).contains(test));
        // Add delete marker
        new S3DefaultDeleteFeature(session).delete(Collections.singletonList(new Path(test).withAttributes(PathAttributes.EMPTY)), new DisabledLoginCallback(), new Delete.DisabledCallback());
        assertFalse(new DefaultFindFeature(session).find(new Path(test).withAttributes(PathAttributes.EMPTY)));
        assertFalse(new S3FindFeature(session).find(new Path(test).withAttributes(PathAttributes.EMPTY)));
        assertTrue(new DefaultFindFeature(session).find(test));
        assertTrue(new S3FindFeature(session).find(test));
    }

    @Test
    public void testDirectoryDeleteWithVersioning() throws Exception {
        final Path bucket = new Path("versioning-test-us-east-1-cyberduck", EnumSet.of(Path.Type.directory, Path.Type.volume));
        final Path parent = new S3DirectoryFeature(session, new S3WriteFeature(session)).mkdir(new Path(bucket,
                new AlphanumericRandomStringService().random(), EnumSet.of(Path.Type.directory)), new TransferStatus());
        final Path test = new S3DirectoryFeature(session, new S3WriteFeature(session)).mkdir(new Path(parent,
                new AlphanumericRandomStringService().random(), EnumSet.of(Path.Type.directory)), new TransferStatus());
        assertNotNull(test.attributes().getVersionId());
        assertTrue(test.isPlaceholder());
        // Only placeholder is found in list output with no version id set
        final Path placeholder = new S3ListService(session).list(parent, new DisabledListProgressListener()).find(new SimplePathPredicate(test));
        assertTrue(placeholder.isPlaceholder());
        assertTrue(new S3FindFeature(session).find(test));
        assertTrue(new DefaultFindFeature(session).find(test));
        // This will only cause a delete marker being added
        new S3DefaultDeleteFeature(session).delete(Arrays.asList(new Path(test).withAttributes(PathAttributes.EMPTY), parent), new DisabledLoginCallback(), new Delete.DisabledCallback());
        // Specific version is still found
        assertTrue(new S3FindFeature(session).find(test));
        assertTrue(new DefaultFindFeature(session).find(test));
        assertFalse(new S3FindFeature(session).find(new Path(test).withAttributes(PathAttributes.EMPTY)));
        assertFalse(new DefaultFindFeature(session).find(new Path(test).withAttributes(PathAttributes.EMPTY)));
    }

    @Test
    public void testCreatePlaceholderEqualSign() throws Exception {
        final String name = String.format("%s=", new AlphanumericRandomStringService().random());
        final Path container = new Path("test-eu-central-1-cyberduck", EnumSet.of(Path.Type.directory, Path.Type.volume));
        final Path test = new S3DirectoryFeature(session, new S3WriteFeature(session)).mkdir(new Path(container, name, EnumSet.of(Path.Type.directory)), new TransferStatus());
        assertTrue(new S3FindFeature(session).find(test));
        assertTrue(new DefaultFindFeature(session).find(test));
        assertTrue(new S3ObjectListService(session).list(test, new DisabledListProgressListener()).isEmpty());
        new S3DefaultDeleteFeature(session).delete(Collections.singletonList(test), new DisabledLoginCallback(), new Delete.DisabledCallback());
    }

    @Test
    public void testCreatePlaceholderVirtualHost() throws Exception {
        final Path test = new S3DirectoryFeature(virtualhost, new S3WriteFeature(virtualhost)).mkdir(
                new Path(new AlphanumericRandomStringService().random(), EnumSet.of(Path.Type.directory, Path.Type.volume)), new TransferStatus());
        assertTrue(new S3FindFeature(virtualhost).find(test));
        assertTrue(new DefaultFindFeature(virtualhost).find(test));
        assertTrue(new S3ObjectListService(virtualhost).list(test, new DisabledListProgressListener()).isEmpty());
        new S3DefaultDeleteFeature(virtualhost).delete(Collections.singletonList(test), new DisabledLoginCallback(), new Delete.DisabledCallback());
    }

    @Test
    public void testBackslash() throws Exception {
        final Path container = new Path("test-eu-central-1-cyberduck", EnumSet.of(Path.Type.volume, Path.Type.directory));
        final Path test = new S3DirectoryFeature(session, new S3WriteFeature(session)).mkdir(
                new Path(container, String.format("%s\\%s", new AlphanumericRandomStringService().random(),
                        new AlphanumericRandomStringService().random()), EnumSet.of(Path.Type.directory)), new TransferStatus());
        assertTrue(new S3FindFeature(session).find(test));
        new S3DefaultDeleteFeature(session).delete(Collections.singletonList(test), new DisabledLoginCallback(), new Delete.DisabledCallback());
        assertFalse(new S3FindFeature(session).find(test));
    }
}

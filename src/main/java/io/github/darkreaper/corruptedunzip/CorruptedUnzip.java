package io.github.darkreaper.corruptedunzip;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.darkreaper.corruptedunzip.Utils.AutoCloseableConcurrentQueue;
import io.github.darkreaper.corruptedunzip.Utils.AutoCloseableExecutorService;
import io.github.darkreaper.corruptedunzip.Utils.AutoCloseableFutureListWithCompletionBarrier;
import io.github.darkreaper.corruptedunzip.Utils.SingletonMap;

public class CorruptedUnzip {

    private static final int NUM_THREADS = Math.max(6,
            (int) Math.ceil(Runtime.getRuntime().availableProcessors() * 1.5f));

    public static void corruptedUnzip(final Path inputZipfilePath, final Path outputDirPath, final boolean overwrite,
            final boolean verbose) {

        final var inputZipfile = inputZipfilePath.toFile();
        if (!inputZipfile.exists()) {
            System.err.println("Input zipfile not found: " + inputZipfile);
            System.exit(1);
        }

        Path unzipDirPath = null;
        try {
            if (outputDirPath == null) {
                final var fileLeafName = inputZipfile.getName();
                final var lastDotIdx = fileLeafName.lastIndexOf('.');
                final var filenameExtension = lastDotIdx > 0 ? fileLeafName.substring(lastDotIdx + 1) : "";
                final var unzipDirName = filenameExtension.equalsIgnoreCase("zip")
                        || filenameExtension.equalsIgnoreCase("jar") ? fileLeafName.substring(0, lastDotIdx)
                                : fileLeafName + "-files";
                unzipDirPath = new File(inputZipfile.getParentFile(), unzipDirName).toPath().toAbsolutePath();
            } else {
                unzipDirPath = outputDirPath.toAbsolutePath();
            }

            final var unzipDirFile = unzipDirPath.toFile();
            if (!unzipDirFile.exists()) {
                final var mkdirsOk = unzipDirFile.mkdirs();
                if (!mkdirsOk) {
                    System.err.println("Could not create output directory: " + unzipDirFile);
                    System.exit(1);
                }
            }
        } catch (final Throwable e) {
            System.err.println("Could not create output directory: " + e);
            e.printStackTrace();
            System.exit(1);
        }

        if (verbose) {
            System.out.println("Unzipping " + inputZipfile + " to " + unzipDirPath);
        }
        final var unzipDirPathFinal = unzipDirPath;

        final var zipEntries = new ArrayList<ZipEntry>();
        try (var zipFile = new ZipFile(inputZipfile)) {
            for (final var e = zipFile.entries(); e.hasMoreElements();) {
                zipEntries.add(e.nextElement());
            }
        } catch (final IOException e) {
            System.err.println("Could not read zipfile directory entries: " + e);
            System.exit(1);
        }

        final var createdDirs = new SingletonMap<File, Boolean>() {
            @Override
            public Boolean newInstance(final File parentDir) throws Exception {
                var parentDirExists = parentDir.exists();
                if (!parentDirExists) {
                    parentDirExists = parentDir.mkdirs();
                    if (!parentDirExists) {
                        parentDirExists = parentDir.exists();
                    }
                    if (verbose) {
                        final String dirPathRelative = unzipDirPathFinal.relativize(parentDir.toPath()).toString()
                                + "/";
                        if (!parentDirExists) {
                            System.out.println(" Cannot create: " + dirPathRelative);
                        } else if (!parentDir.isDirectory()) {
                            System.out.println("Already exists: " + dirPathRelative);
                        } else {
                            System.out.println("      Creating: " + dirPathRelative);
                        }
                    }
                    if (!parentDir.isDirectory()) {
                        parentDirExists = false;
                    }
                }
                return parentDirExists;
            }
        };

        try (final var openZipFiles = new AutoCloseableConcurrentQueue<ZipFile>();
                final var executor = new AutoCloseableExecutorService("CorruptedUnzip", NUM_THREADS);
                final var futures = new AutoCloseableFutureListWithCompletionBarrier(zipEntries.size())) {
            for (final var zipEntry : zipEntries) {
                futures.add(executor.submit(() -> {
                    final ThreadLocal<ZipFile> zipFileTL = ThreadLocal.withInitial(() -> {
                        try {
                            final ZipFile zipFile = new ZipFile(inputZipfile);
                            openZipFiles.add(zipFile);
                            return zipFile;
                        } catch (final IOException e) {
                            System.err.println("Could not open zipfile " + inputZipfile + " : " + e);
                            System.exit(1);
                        }
                        return null;
                    });
                    var entryName = zipEntry.getName();
                    while (entryName.startsWith("/")) {
                        entryName = entryName.substring(1);
                    }
                    try {
                        final var entryPath = unzipDirPathFinal.resolve(entryName);
                        if (!entryPath.startsWith(unzipDirPathFinal)) {
                            if (verbose) {
                                System.out.println("      Bad path: " + entryName);
                            }
                        } else if (zipEntry.isDirectory()) {
                            createdDirs.getOrCreateSingleton(entryPath.toFile());
                        } else {
                            final var entryFile = entryPath.toFile();
                            final var parentDir = entryFile.getParentFile();
                            final var parentDirExists = createdDirs.getOrCreateSingleton(parentDir);
                            if (parentDirExists) {
                                try (var inputStream = zipFileTL.get().getInputStream(zipEntry)) {
                                    if (overwrite) {
                                        if (verbose) {
                                            System.out.println("     Unzipping: " + entryName);
                                        }
                                        Files.copy(inputStream, entryPath, StandardCopyOption.REPLACE_EXISTING);
                                    } else {
                                        if (!entryFile.exists()) {
                                            if (verbose) {
                                                System.out.println("     Unzipping: " + entryName);
                                            }
                                            Files.copy(inputStream, entryPath);
                                        } else if (verbose) {
                                            System.out.println("Already exists: " + entryName);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (final InvalidPathException ex) {
                        if (verbose) {
                            System.out.println("  Invalid path: " + entryName);
                        }
                    }
                    return null;
                }));
            }
        }
        System.out.flush();
        System.err.flush();
    }

    public static void main(final String[] args) {
        final var unmatchedArgs = new ArrayList<String>();
        boolean overwrite = false;
        boolean verbose = true;
        for (final var arg : args) {
            if (arg.equals("-o")) {
                overwrite = true;
            } else if (arg.equals("-q")) {
                verbose = false;
            } else if (arg.startsWith("-")) {
                System.err.println("Unknown switch: " + arg);
                System.exit(1);
            } else {
                unmatchedArgs.add(arg);
            }
        }
        if (unmatchedArgs.size() != 1 && unmatchedArgs.size() != 2) {
            System.err.println(
                    "Syntax: java -jar corruptedunzip-1.0-SNAPSHOT.jar [-o] [-q] zipfilename.zip [outputdir]");
            System.err.println(" Where:  -q => quiet");
            System.err.println("         -o => overwrite");
            System.exit(1);
        }
        corruptedUnzip(Paths.get(unmatchedArgs.get(0)),
                unmatchedArgs.size() == 2 ? Paths.get(unmatchedArgs.get(1)) : null, overwrite, verbose);
    }
}

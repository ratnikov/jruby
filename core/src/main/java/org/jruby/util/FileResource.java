package org.jruby.util;

import jnr.posix.FileStat;
import java.io.FileNotFoundException;

/**
 * This is a shared interface for files loaded as {@link java.io.File} and {@link java.util.zip.ZipEntry}.
 */
public interface FileResource {
    String absolutePath();

    boolean exists();
    boolean isDirectory();
    boolean isFile();

    long lastModified();
    long length();

    boolean canRead();
    boolean canWrite();

    /**
     * @see java.io.File.list
     */
    String[] list();

    boolean isSymLink();

    FileStat stat();
    FileStat lstat();

    // ---- Visitor pattern ----

    <T> T accept(Visitor<T> visitor);

    interface Visitor<T> {
        T visit(JarFileResource resource);
        T visit(JarDirectoryResource resource);
        T visit(RegularFileResource resource);
    }
}

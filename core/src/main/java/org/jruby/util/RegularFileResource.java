package org.jruby.util;

import jnr.posix.FileStat;
import jnr.posix.POSIX;
import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;

import org.jruby.Ruby;
import org.jruby.RubyFile;

import jnr.posix.JavaSecuredFile;
import org.jruby.platform.Platform;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.util.zip.ZipEntry;
import java.io.IOException;

/**
 * Represents a "regular" file, backed by regular file system.
 */
class RegularFileResource implements FileResource {
    private final JRubyFile file;
    private final POSIX posix;
    private FileStat lazyLstat = null;
    private FileStat lazyStat = null;

    // HACK HACK HACK
    private final static Ruby globalRuntime = Ruby.getGlobalRuntime();

    RegularFileResource(File file) {
        this(file.getAbsolutePath());
    }

    protected RegularFileResource(String filename) {
        this.file = new JRubyFile(filename);

        // Maybe this should be passed in instead?
        this.posix = globalRuntime.getPosix();
    }

    @Override
    public String absolutePath() {
        return file.getAbsolutePath();
    }

    @Override
    public long length() {
        return file.length();
    }

    @Override
    public long lastModified() {
        return file.lastModified();
    }

    @Override
    public boolean exists() {
        return file.exists();
    }

    @Override
    public boolean isFile() {
        return file.isFile();
    }

    @Override
    public boolean isDirectory() {
        return file.isDirectory();
    }

    @Override
    public boolean isSymLink() {
        // java.io.File doesn't readily tells whether a file is a symlink,
        // so rely on getCanonicalFile to expand symbolic links behavior to judge
        try {
            File canon;
            if (file.getParent() == null) {
                canon = file;
            } else {
                File canonDir;
                canonDir = file.getParentFile().getCanonicalFile();

                canon = new File(canonDir, file.getName());
            }
            return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
        } catch (IOException e) {
            return false;
        }

    }

    @Override
    public boolean canRead() {
        return file.canRead();
    }

    @Override
    public boolean canWrite() {
        return file.canWrite();
    }

    @Override
    public String[] list() {
        return file.list();
    }

    @Override
    public FileStat stat() {
        if (lazyStat == null) lazyStat = posix.stat(absolutePath());
        return lazyStat;
    }

    @Override
    public FileStat lstat() {
        if (lazyLstat == null) lazyLstat = posix.lstat(absolutePath());
        return lazyLstat;
    }

    @Override
    public String toString() {
        return file.toString();
    }
}

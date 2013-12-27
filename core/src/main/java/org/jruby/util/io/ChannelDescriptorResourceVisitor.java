package org.jruby.util.io;

import jnr.posix.POSIX;
import org.jruby.util.JRubyFile;
import org.jruby.util.FileResource;
import org.jruby.util.RegularFileResource;
import org.jruby.util.JarDirectoryResource;
import org.jruby.util.JarFileResource;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;

class ChannelDescriptorResourceVisitor implements FileResource.Visitor<ChannelDescriptor> {
    private final ModeFlags flags;
    private final int perm;
    private final POSIX posix;

    private IOException ioError = null;

    public ChannelDescriptorResourceVisitor(ModeFlags flags, int perm, POSIX posix) {
        this.flags = flags;
        this.perm = perm;
        this.posix = posix;
    }

    @Override
    public ChannelDescriptor visit(JarDirectoryResource dirResource) {
        // Cannot really do much with a directory as a channel
        return new ChannelDescriptor(new NullChannel(), flags);
    }

    @Override
    public ChannelDescriptor visit(JarFileResource jarFileResource) {
        try {
            // FIXME: don't use RubyIO for this
            return new ChannelDescriptor(Channels.newChannel(jarFileResource.getInputStream()), flags);
        } catch (IOException ioError) {
            this.ioError = ioError;
            return null;
        }
    }

    @Override
    public ChannelDescriptor visit(RegularFileResource fileResource) {
        try {
            JRubyFile theFile = fileResource.getFile();

            if (theFile.isDirectory() && flags.isWritable()) {
                throw new DirectoryAsFileException();
            }

            boolean fileCreated = false;
            if (flags.isCreate()) {
                try {
                    fileCreated = theFile.createNewFile();

                    if (!fileCreated && flags.isExclusive()) {
                        throw new FileExistsException(theFile.getPath());
                    }
                } catch (IOException ioe) {
                    // See JRUBY-4380.
                    // MRI behavior: raise Errno::ENOENT in case
                    // when the directory for the file doesn't exist.
                    // Java in such cases just throws IOException.
                    File parent = theFile.getParentFile();
                    if (parent != null && parent != theFile && !parent.exists()) {
                        throw new FileNotFoundException(theFile.getPath());
                    } else if (!theFile.canWrite()) {
                        throw new PermissionDeniedException(theFile.getPath());
                    } else {
                        // for all other IO errors, just re-throw the original exception
                        throw ioe;
                    }
                }
            } else {
                if (!theFile.exists()) {
                    throw new FileNotFoundException(theFile.getPath());
                }
            }

            FileDescriptor fileDescriptor;
            FileChannel fileChannel;

            /* Because RandomAccessFile does not provide a way to pass append
             * mode, we must manually seek if using RAF. FileOutputStream,
             * however, does properly honor append mode at the lowest levels,
             * reducing append write costs when we're only doing writes.
             * 
             * The code here will use a FileOutputStream if we're only writing,
             * setting isInAppendMode to true to disable our manual seeking.
             * 
             * RandomAccessFile does not handle append for us, so if we must
             * also be readable we pass false for isInAppendMode to indicate
             * we need manual seeking.
             */
            boolean isInAppendMode;
            if (flags.isWritable() && !flags.isReadable()) {
                FileOutputStream fos = new FileOutputStream(theFile, flags.isAppendable());
                fileChannel = fos.getChannel();
                fileDescriptor = fos.getFD();
                isInAppendMode = true;
            } else {
                RandomAccessFile raf = new RandomAccessFile(theFile, flags.toJavaModeString());
                fileChannel = raf.getChannel();
                fileDescriptor = raf.getFD();
                isInAppendMode = false;
            }

            // call chmod after we created the RandomAccesFile
            // because otherwise, the file could be read-only
            if (fileCreated) {
                // attempt to set the permissions, if we have been passed a POSIX instance,
                // perm is > 0, and only if the file was created in this call.
                if (posix != null && perm > 0) {
                    posix.chmod(theFile.getPath(), perm);
                }
            }

            try {
                if (flags.isTruncate()) fileChannel.truncate(0);
            } catch (IOException ioe) {
                if (ioe.getMessage().equals("Illegal seek")) {
                    // ignore; it's a pipe or fifo that can't be truncated
                } else {
                    throw ioe;
                }
            }

            // TODO: append should set the FD to end, no? But there is no seek(int) in libc!
            //if (modes.isAppendable()) seek(0, Stream.SEEK_END);

            return new ChannelDescriptor(fileChannel, flags, fileDescriptor, isInAppendMode);
        } catch (IOException ioError) {
            this.ioError = ioError;
            return null;
        }
    }

    public ChannelDescriptor visitOrThrow(FileResource resource) throws IOException {
        ChannelDescriptor cd = resource.accept(this);
        if (ioError != null) {
            throw ioError;
        }
        return cd;
    }
}

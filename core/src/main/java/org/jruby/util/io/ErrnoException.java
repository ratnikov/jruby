package org.jruby.util.io;

import org.jruby.Ruby;
import org.jruby.exceptions.RaisableException;
import org.jruby.exceptions.RaiseException;

class ErrnoException extends RaisableException {
    static class FileIsDirectory extends ErrnoException {
        public FileIsDirectory(String path) { super("EISDIR", path); }
    }

    static class FileExists extends ErrnoException {
        public FileExists(String path) { super("EEXIST", path); }
    }

    private final String path;
    private final String errnoClass;

    protected ErrnoException(String errnoClass, String path) {
        this.errnoClass = errnoClass;
        this.path = path;
    }

    @Override
    public RaiseException newRaiseException(Ruby runtime) {
        return runtime.newRaiseException(runtime.getErrno().getClass(errnoClass), path);
    }
}

package android.content;

import java.io.File;

public class Context {
    private final File filesDir;
    public Context(File filesDir) { this.filesDir = filesDir; }
    public File getFilesDir() { return filesDir; }
}

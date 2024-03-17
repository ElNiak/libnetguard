package eu.faircode.netguard;

import java.io.DataInput;
import java.io.IOException;

public class Package {

    static Package decodePackage(DataInput dataInput) throws IOException {
        return new Package(dataInput.readUTF(), dataInput.readUTF(), dataInput.readLong());
    }

    private final String packageName;
    private final String label;
    private final long versionCode;

    private Package(String packageName, String label, long versionCode) {
        this.packageName = packageName;
        this.label = label;
        this.versionCode = versionCode;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getLabel() {
        return label;
    }

    public long getVersionCode() {
        return versionCode;
    }

    @Override
    public String toString() {
        return label + "(" + packageName + ")";
    }
}

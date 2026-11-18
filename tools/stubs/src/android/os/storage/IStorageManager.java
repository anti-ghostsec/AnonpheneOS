// Compile-only stub; see com/android/internal/widget/LockPatternUtils.java.
package android.os.storage;

public interface IStorageManager extends android.os.IInterface {
    void runMaintenance() throws android.os.RemoteException;

    abstract class Stub extends android.os.Binder implements IStorageManager {
        public static IStorageManager asInterface(android.os.IBinder obj) { throw new RuntimeException("stub"); }
    }
}

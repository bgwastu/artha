package de.robv.android.xposed;

public class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Object self;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        public Object thisObject;
        public boolean returnEarly;

        public void setResult(Object result) { this.result = result; this.returnEarly = true; }
        public Object getResult() { return result; }
    }
}

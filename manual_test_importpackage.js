// Test script for manual verification of importPackage functionality

// Test 1: Try importPackage
try {
    print("Testing importPackage...");
    importPackage(java.util);
    var list = new ArrayList();
    list.add("test");
    print("SUCCESS: importPackage worked! List size: " + list.size());
} catch (e) {
    print("FAILED: importPackage error: " + e);
}

// Test 2: Try direct access
try {
    print("Testing direct access...");
    var directList = new java.util.ArrayList();
    directList.add("direct test");
    print("SUCCESS: Direct access worked! List: " + directList.toString());
} catch (e) {
    print("FAILED: Direct access error: " + e);
}

// Test 3: Try java.lang access
try {
    print("Testing java.lang access...");
    var version = java.lang.System.getProperty("java.version");
    print("SUCCESS: Java version: " + version);
} catch (e) {
    print("FAILED: java.lang access error: " + e);
}

// Test importPackage step by step
print("Testing importPackage functionality...");

// First check if importPackage is defined
print("1. importPackage defined: " + (typeof importPackage));

// Test direct Java access
try {
    var directList = new java.util.ArrayList();
    print("2. Direct java.util.ArrayList works: " + directList.getClass().getName());
} catch (e) {
    print("2. Direct java.util.ArrayList failed: " + e);
}

// Test importPackage call
try {
    importPackage(java.util);
    print("3. importPackage(java.util) called successfully");
} catch (e) {
    print("3. importPackage(java.util) failed: " + e);
}

// Check if ArrayList is now defined
print("4. ArrayList defined after import: " + (typeof ArrayList));

// Try to create ArrayList
try {
    var importedList = new ArrayList();
    print("5. new ArrayList() works: " + importedList.getClass().getName());
} catch (e) {
    print("5. new ArrayList() failed: " + e);
}

// Test the exact same code as the test
try {
    importPackage(java.util); 
    var result = new ArrayList();
    print("6. Test code result: " + result);
    print("6. Test code result type: " + (typeof result));
    print("6. Test code result class: " + result.getClass().getName());
} catch (e) {
    print("6. Test code failed: " + e);
}

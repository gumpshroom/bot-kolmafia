// Test if importPackage is defined
if (typeof importPackage === 'undefined') {
    print("importPackage is NOT defined");
} else {
    print("importPackage IS defined");
}

// Test basic Java access
try {
    var list = new java.util.ArrayList();
    print("Direct Java access works: " + list.toString());
} catch (e) {
    print("Direct Java access failed: " + e);
}

// Test importPackage
try {
    importPackage(java.util);
    var list2 = new ArrayList();
    print("importPackage works: " + list2.toString());
} catch (e) {
    print("importPackage failed: " + e);
}

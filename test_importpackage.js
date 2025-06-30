// Test script to verify importPackage functionality
importPackage(java.util);

// Test 1: Create an ArrayList using importPackage
var list = new ArrayList();
list.add("Hello");
list.add("World");
print("ArrayList size: " + list.size());

// Test 2: Access Java classes directly  
var map = new java.util.HashMap();
map.put("key1", "value1");
map.put("key2", "value2");
print("HashMap size: " + map.size());

// Test 3: Access java.lang (should be available by default)
var version = java.lang.System.getProperty("java.version");
print("Java version: " + version);

print("All importPackage tests completed successfully!");

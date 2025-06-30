// Simple test to check if importPackage works
try {
  importPackage(java.util);
  var list = new ArrayList();
  print("Success: ArrayList created");
} catch (e) {
  print("Error: " + e);
}

try {
  var directList = new java.util.ArrayList();
  print("Success: Direct java.util.ArrayList created");
} catch (e) {
  print("Error with direct access: " + e);
}

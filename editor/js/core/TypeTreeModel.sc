import sc.layer.CodeType;
import sc.layer.CodeFunction;

import sc.obj.Sync;
import sc.obj.SyncMode;

// The Javascript implementation of the type/layer tree in the UI.  
TypeTreeModel {
   excludePackages = {"org.eclipse.jetty", "javax.servlet", "sc.obj", "java.util", "java.io", "sc.html", "sc.jetty", "sc.lang", "sc.servlet"};

   // The swing version does a rebuild on the first refresh call but the web version doesn't
   // need to do that.
   rebuildFirstTime = false;

   public final static String PKG_INDEX_PREFIX = "<pkg>:";

   boolean nodeExists(String typeName) {
      return typeTree.rootTreeIndex.get(typeName) != null;
   }
}

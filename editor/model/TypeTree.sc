import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.EnumSet;
import java.util.Collections;

import sc.layer.Layer;
import sc.layer.SrcEntry;

import sc.util.ArrayList;
import sc.util.LinkedHashMap;
import sc.util.StringUtil;
import sc.type.CTypeUtil;

import sc.obj.Constant;

import sc.lang.java.ModelUtil;
import sc.lang.InstanceWrapper;

import sc.layer.CodeType;
import sc.layer.CodeFunction;

import sc.dyn.DynUtil;

import sc.sync.SyncManager;

class TypeTree {
   TypeTreeModel treeModel;
   boolean byLayer = false;

   // These are the two main trees of TreeEnt objects.  This tree is not directly displayed but is referenced from the TreeNode classes which are displayed.
   TreeEnt rootDirEnt;

   // These will be populated from the server automatically.  Sometime after those values
   // are set, we need to refresh the tree.
   rootDirEnt =: treeModel.refresh();

   TypeTreeSelectionListener selectionListener;

   TreeEnt emptyCommentNode;

   // These are transient so they are not synchronized from client to the server.  That's
   // because we will build these data structures on the server or client
   transient TreeNode rootTreeNode;

   transient Map<String, List<TreeNode>> rootTreeIndex;

   public TypeTree(TypeTreeModel model) {
      treeModel = model;
   }

   enum EntType {
      Root,
      Comment,
      LayerGroup,
      LayerDir,
      LayerFile,
      InactiveLayer, // Layer in the index of all layers - one which has not yet been adding to the system
      ParentType,    // a class with children
      Type,          // a leaf class/object
      ParentObject,
      Object,
      ParentEnum,
      Enum,
      ParentEnumConstant,
      EnumConstant,
      ParentInterface,
      Interface,
      Package,
      Primitive,
      Instance;
   }

   @Sync(syncMode=SyncMode.Disabled)
   EditorModel getEditorModel() {
      return treeModel.editorModel;
   }

   // Disabling because this type is not sync'd to the client in this configuration.  Instead we sync the TreeEnt's
   // and then filter then on the client to produce the TreeNodes.  The TreeNodes correspond to UI objects and so
   // will turn into native UI elements as well that reflect this basic interface
   @Sync(syncMode=SyncMode.Disabled)
   class TreeNode {
      TreeEnt ent;
      // Flag used temporarily to do fast and orderly removal of elements that are no longer being used
      boolean marked;

      boolean getHasChildren() {
         return ent.hasChildren;
      }

      boolean getNeedsOpenClose() {
         return ent.needsOpenClose;
      }

      abstract int getNumChildren();

      abstract TreeNode getChildNode(int ix);

      int findChildIndexForEnt(TreeEnt ent) {
          int ct = getNumChildren();
          for (int i = 0; i < ct; i++) {
             if (getChildNode(i).ent == ent)
                return i;
          }
          return -1;
      }

      TreeNode findChildNodeForEnt(TreeEnt ent) {
          int ct = getNumChildren();
          for (int i = 0; i < ct; i++) {
             TreeNode node = getChildNode(i);
             if (node.ent == ent)
                return node;
          }
          return null;
      }

      void removeChildNode(TreeEnt childEnt) {
          int ix = findChildIndexForEnt(childEnt);
          if (ix != -1)
             removeChildAt(ix);
      }

      abstract void removeChildAt(int ix);

      void clearMarkedFlag() {
         int numChildren = getNumChildren();
         for (int i = 0; i < numChildren; i++) {
            TreeNode child = getChildNode(i);
            child.marked = false;
         }
      }

      boolean removeUnmarkedChildren() {
         int numChildren = getNumChildren();
         boolean any = false;
         for (int i = 0; i < numChildren; i++) {
            TreeNode child = getChildNode(i);
            if (!child.marked) {
               removeChildAt(i);
               i--;
               numChildren--;
               any = true;
            }
         }
         return any;
      }

      void sendChangedEvent() {
        sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      }
   }

   @sc.obj.Sync(onDemand=true)
   class TreeEnt implements Comparable<TreeEnt>, sc.obj.IObjectId {
      EntType type;
      String value;
      String srcTypeName;
      Layer layer;
      // For TreeEnts which represent instances, the wrapper for the instance
      InstanceWrapper instance;

      @sc.obj.Sync(onDemand=true)
      HashMap<String,TreeEnt> childEnts;
      @sc.obj.Sync(onDemand=true)
      // Parallel to the childEnts map, but retains sort order for display
      ArrayList<TreeEnt> childList;
      ArrayList<TreeEnt> removed = null;

      boolean imported;
      boolean hasSrc;
      boolean transparent; // Entry does not exist on the file system yet
      TypeTree typeTree; // Or the layer tree
      boolean prependPackage; // Is this a type in the type tree or a file like web.xml which does not use a type name
      boolean marked; // Temp flag used to mark in-use objects
      String objectId;

      // Stores the swing entity that corresponds to this treeEnt (or null if not associated with one)
      @Sync(syncMode=SyncMode.Disabled)
      TreeNode treeNode;

      TreeEnt(EntType type, String value, TypeTree typeTree, String srcTypeName, Layer layer) {
         this.type = type;
         this.value = value;
         this.typeTree = typeTree;
         this.srcTypeName = srcTypeName;
         this.layer = layer;
      }

      ArrayList<CodeType> entCodeTypes; // Which types and functions is this ent visible?
      ArrayList<CodeFunction> entCodeFunctions;

      // The value of getTypeDeclaration, once it's been fetched
      Object cachedTypeDeclaration;

      cachedTypeDeclaration =: typeAvailable();

      // Set to true when you need the type fetched
      boolean needsType = false;

      boolean selected = false;

      boolean closed = false; // Have we explicitly closed this node.  if so, don't reopen it

      boolean createModeSelected = false;

      UIIcon icon;

      boolean hasVisibleChildren;

      // When the childEnts property is populated or changes we need to
      // refresh.
      childEnts =: treeModel.refresh();

      String toString() {
         return value;
      }

      void toggleOpen() {
         if (!open)
            open = true;
         else {
            open = false;
            closed = true; // Track when we explicit close it and then don't re-open it again
         }
         selectType(false);
      }

      private boolean open = false;

      void setOpen(boolean newOpen) {
         boolean orig = open;
         open = newOpen;
         if (!orig && newOpen) {
            initChildren();
         }
      }

      boolean getOpen() {
         return open;
      }

      public void initChildren() {
         if (childEnts == null) {
            // childEnts and childList are marked @Sync(onDemand=true) so they are left out of the sync when the
            // parent object is synchronized.  When a user opens the node, the startSync call begins
            // synchronizing this property.  On the client this causes a fetch of the data.
            // On the server, it pushes this property to the client on the next sync.
            // When childEnts is set, the change of that property calls refresh().
            SyncManager.startSync(this, "childEnts");
            SyncManager.startSync(this, "childList");
         }
      }

      void selectType(boolean append) {
         if (selectionListener != null)
            selectionListener.selectTreeEnt(this, append);
      }

      void typeAvailable() {
         if (cachedTypeDeclaration == null)
            return;
         if (selectionListener != null)
            selectionListener.treeTypeAvailable(this);
      }

      String getTypeName() {
         String typeName = srcTypeName;
         //if (layer != null)
         //   typeName = TypeUtil.prefixPath(layer.packagePrefix, srcTypeName);

         return typeName;
      }

      String getPackageName() {
         Object type = getTypeDeclaration();
         if (type != null)
            return ModelUtil.getPackageName(type);
         return null;
      }

      boolean isSelectable() {
         return type != EntType.LayerGroup && type != EntType.Root;
      }

      int compareTo(TreeEnt c) {
         return value.compareTo(c.value);
      }

      boolean getNeedsOpenClose() {
         return type != EntType.Root && hasChildren;
      }

      public boolean isVisible(boolean byLayer) {
          // Always keep the selected guys visible
          if (editorModel.isTypeNameSelected(getTypeName()))
             return true;

          // This is a per-layer type so only show it if the layer matches
          if (layer != null && !layer.matchesFilter(treeModel.codeTypes, treeModel.codeFunctions))
             return false;

          if (srcTypeName != null) {
             switch (type) {
                case Root:
                case Comment:
                   return true;
                case Package:
                case LayerGroup: // A folder that includes layer directories
                   if (!hasAVisibleChild(byLayer))
                      return false;
                   return getTypeIsVisible();

                case InactiveLayer:
                   // Can't add a layer twice - only show inactive layers which are really not active
                   return treeModel.layerMode && treeModel.system.getLayerByDirName(value) == null;

                case LayerDir:   // A layer directory itself
                   if (treeModel.addLayerMode) // the layer filters were applied above.  When adding layers though, don't show layers that are already there
                      return false;
                   return getTypeIsVisible();

                case LayerFile:
                   if (treeModel.createMode) {
                      // In the type tree, we display the layer file.  In the layer tree, We display the layerDir as the layer in layer mode
                      if (treeModel.layerMode)
                         return !byLayer && !treeModel.addLayerMode;
                      else
                         return false;
                   }
                   return getTypeIsVisible();

                case ParentObject:
                   if (treeModel.layerMode)
                      return false;
                   if (hasAVisibleChild(byLayer))
                      return true;
                case Object:
                   if (treeModel.createMode || treeModel.layerMode)
                       return false;
                   // FALL THROUGH to do type processing in app type mode.
                case ParentType:
                case ParentEnum:
                case ParentEnumConstant:
                case ParentInterface:
                   if (treeModel.layerMode)
                      return false;

                   if (hasAVisibleChild(byLayer))
                      return true;
                case Interface:
                case Enum:
                case EnumConstant:
                case Type:
                   if (treeModel.layerMode)
                      return false;

                   if (!getTypeIsVisible())
                      return false;

                   // Create mode:
                   //    show imported types or those which are in the current layer, or those imported which are defined in this layer
                   // Application mode:
                   //    just make sure we have the src for the type.  We'll have already doen the application check above.
                   //    if it's a transparent item, even if hasSrc is false we display it.
                   return (treeModel.createMode && (imported || editorModel.currentLayer == null || layer == editorModel.currentLayer)) || hasSrc || transparent;

                case Primitive:
                   return treeModel.createMode && treeModel.propertyMode;
                case Instance:
                   return treeModel.includeInstances;
             }
          }
          return true;
      }

      Object getTypeDeclaration() {
         if (cachedTypeDeclaration != null)
            return cachedTypeDeclaration;
         if (typeName == null)
            return null;
         return DynUtil.resolveName(typeName, false);
      }

      boolean getTypeIsVisible() {
         if (entCodeTypes != null && treeModel.codeTypes != null) {
            boolean vis = false;
            for (int i = 0; i < entCodeTypes.size(); i++) {
               if (treeModel.codeTypes.contains(entCodeTypes.get(i))) {
                  vis = true;
                  break;
               }
            }
            if (!vis)
               return false;
         }
         if (entCodeFunctions != null && treeModel.codeFunctions != null) {
            boolean vis = false;
            for (int i = 0; i < entCodeFunctions.size(); i++) {
               if (treeModel.codeFunctions.contains(entCodeFunctions.get(i))) {
                  vis = true;
                  break;
               }
            }
            if (!vis)
               return false;
         }
         return true;
      }

      public String getIdPrefix() {
          return "T";
      }

      @Constant
      String getObjectId() {
         if (objectId != null)
            return objectId;
         if (type == null || value == null)
            return null;
         String valuePart = CTypeUtil.escapeIdentifierString(srcTypeName == null ? (value == null ? "" : "_" + value.toString()) : "_" + srcTypeName);
         String typePart = type == null ? "_unknown" : "_" + type;
         String layerPart = layer == null ? "" : "_" + CTypeUtil.escapeIdentifierString(layer.layerName);
         if (typePart.contains("null"))
            System.out.println("***");
         objectId = "TE" + getIdPrefix() + typePart + layerPart + valuePart;
         return objectId;
      }

      void addChild(TreeEnt ent) {
         if (childEnts == null)
            childEnts = new HashMap<String,TreeEnt>();

         TreeEnt old = childEnts.put(ent.nodeId, ent);
         if (childList == null)
            childList = new ArrayList<TreeEnt>();
         if (old != null)
            childList.remove(old);
         childList.add(ent);
      }

      void removeChild(TreeEnt ent) {
         childEnts.remove(ent.nodeId);
         if (childList != null)
            childList.remove(ent);
         removeEntry(ent);
      }

      void removeChildren() {
         if (childEnts != null)
            childEnts.clear();
         if (childList != null)
            childList.clear();
      }

      void removeEntry(TreeEnt toRem) {
         if (removed == null) {
            removed = new ArrayList<TreeEnt>(1);
         }
         removed.add(toRem);
      }

      boolean hasChild(String value) {
         if (childEnts != null) {
            for (TreeEnt childEnt:childEnts.values()) {
               if (childEnt.value.equals(value))
                  return true;
            }
         }
         return false;
      }

      boolean getHasChildren() {
         // Need to assume we have children until they are fetched
         return getNumChildren() > 0 || childList == null;
         //return true;
      }

      public int getNumChildren() {
         return (childEnts == null ? 0 : childEnts.size());
      }

      public List<TreeEnt> getChildren() {
         ArrayList<TreeEnt> children = new ArrayList<TreeEnt>();
         if (childEnts != null)
            children.addAll(childEnts.values());
         return children;
      }

      boolean hasAVisibleChild(boolean byLayer) {
         switch (type) {
            case Root:
               return true; // should the root always be visible?
         }
         // Not yet fetched so we need to assume there is something visible here
         if (childEnts == null)
             return true;
         for (TreeEnt childEnt:childEnts.values()) {
             if (childEnt.isVisible(byLayer) || childEnt.hasAVisibleChild(byLayer))
                return hasVisibleChildren = true;
         }
         return hasVisibleChildren = false;
      }

      void findTypeTreeEnts(List<TreeEnt> res, String typeName) {
         if (this.typeName != null && this.typeName.equals(typeName)) {
            res.add(this);
         }
         if (childEnts != null) {
            for (TreeEnt childEnt:childEnts.values()) {
               childEnt.findTypeTreeEnts(res, typeName);
            }
         }
      }

      boolean updateSelected() {
         boolean needsRefresh = false;
         if (typeName == null) {
            // Once there's a current type, all directories are deselected
            if (editorModel.typeNames.length > 0)
               selected = false;
            // Leave the folder selected as long as it marks the current package
            else if (!DynUtil.equalObjects(srcTypeName, editorModel.currentPackage))
               selected = false;
            return false;
         }
         boolean newSel = editorModel.isTypeNameSelected(typeName);
         if (newSel != selected)
            selected = newSel;
         boolean newCreate = editorModel.isCreateModeTypeNameSelected(typeName);
         if (newCreate != createModeSelected)
            createModeSelected = newCreate;
         if (needsOpen() && !open && !closed) {
            open = true;
            needsRefresh = true;
         }
         if (childEnts != null) {
            for (TreeEnt childEnt:childEnts.values()) {
               if (childEnt.updateSelected())
                  needsRefresh = true;
               // auto-open trees when child nodes are selected
               if (!open && childEnt.needsOpen()) {
                   open = true;
                   needsRefresh = true;
                }
            }
         }
         return needsRefresh;
      }

      boolean needsOpen() {
         if (editorModel.createMode ? createModeSelected : selected)
            return true;
         // auto-open trees when child nodes are selected
         if (childEnts != null) {
            for (TreeEnt childEnt:childEnts.values()) {
               if (childEnt.needsOpen())
                  return true;
            }
         }

         return false;
      }

      public void updateInstances(List<InstanceWrapper> insts) {
         clearMarkedFlag();
         if (insts != null) {
            for (InstanceWrapper inst:insts) {
                TreeEnt childEnt = null;
                if (childList != null) {
                   for (TreeEnt ent:childList) {
                       if (ent.instance != null && ent.instance.equals(inst)) {
                           childEnt = ent;
                           break;
                       }
                   }
                }
                if (childEnt == null) {
                   childEnt = new TreeEnt(EntType.Instance, inst.toString(), typeTree, inst.typeName, null);
                   childEnt.instance = inst;
                   if (childEnt.srcTypeName == null)
                      childEnt.srcTypeName = srcTypeName;
                   addChild(childEnt);
                }
                childEnt.marked = true;
            }
         }
         removeUnmarkedInstances();
      }

      public void clearMarkedFlag() {
         if (childList != null) {
            for (TreeEnt child:childList)
               child.marked = false;
         }
      }

      public void removeUnmarkedInstances() {
          if (childList != null) {
             for (int i = 0; i < childList.size(); i++) {
                TreeEnt child = childList.get(i);
                if (!child.marked && child.instance != null) {
                   childList.remove(i);
                   i--;
                   childEnts.remove(child.nodeId);
                   removeEntry(child);
                }
             }
          }
      }

      public String getNodeId() {
         switch (type) {
            case Instance:
               return instance.toString();
            default:
               return value;
         }
      }

      void sendChangedEvent() {
        sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      }
   }

   String getRootName() {
      String rootName;

      if (treeModel.createMode) {
         if (treeModel.propertyMode)
            rootName = "Select Property Type";
         else if (treeModel.addLayerMode)
            rootName = "Select Layer to Include";
         else if (treeModel.createLayerMode)
            rootName = "Select Extends Layers";
         else
            rootName = "Select Extends Type";
      }
      else
         rootName = "Application Types";

      return rootName;
   }

   public boolean isTypeTree() {
      return !(this instanceof ByLayerTypeTree);
   }

   public boolean rebuildDirEnts() {
      return false;
   }
}

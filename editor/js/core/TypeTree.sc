TypeTree {
   TreeNode {
      TreeNode(TreeEnt treeEnt) {
          ent = treeEnt;
      }
      ArrayList<TreeNode> children = new ArrayList<TreeNode>();;

      int getNumChildren() {
         return children.size();
      }

      TreeNode getChildNode(int ix) {
         return children.get(ix);
      }

      void removeChildNode(TreeEnt childEnt) {
          int ix = findChildIndexForEnt(childEnt);
          if (ix != -1)
             children.remove(ix);
          else
             System.err.println("*** Unable to remove child node");
      }
   }

   void refreshTree() {
      if (treeModel.codeTypes == null || treeModel.codeFunctions == null)
         return;

      if (rootDirEnt == null) {
         if (!rebuildDirEnts()) {
            return;
         }
      }

      rootTreeIndex = new HashMap<String, List<TreeNode>>();

      String rootName = getRootName();;
      TreeNode rootNode;
      if (rootTreeNode == null) {
         // Now build the TreeNodes from that, sorting as we go
         rootNode = new TreeNode(rootDirEnt);
      }
      else {
         rootTreeNode.ent = rootDirEnt;
         rootNode = rootTreeNode;
      }

      TreeNode defaultNode = rootNode.findChildNodeForEnt(emptyCommentNode);
      if (defaultNode != null) {
         rootNode.removeChildNode(emptyCommentNode);
      }
      updatePackageContents(rootDirEnt, rootNode, rootTreeIndex, false);
      if (rootNode.children.size() == 0) {
      /*
       * The layer tree nodes get populated on the server
         if (editorModel.codeFunctions.size() == CodeFunction.allSet.size() && editorModel.codeTypes.size() == CodeType.allSet.size())
            typeEmptyCommentNode.value = "<No types>";
         else
            typeEmptyCommentNode.value = "<No matching types>";
      */
         defaultNode = new TreeNode(emptyCommentNode);
         rootNode.children.add(defaultNode);
      }
      treeModel.uiBuilt = true;

      // Update this after it's completely built so we can bind to this value and see a fully
      // populated tree.
      if (rootTreeNode != rootNode)
         rootTreeNode = rootNode;
   }

   void updatePackageContents(TreeEnt ents, TreeNode treeNode, Map<String, List<TreeNode>> index, boolean byLayer) {
       Map<String,TreeEnt> subDirs = ents.childEnts;
       int pos = 0;
       int rix;
       if (subDirs != null) {
          for (TreeEnt childEnt:subDirs.values()) {
             rix = -1;
             int tix = ents.removed != null ? ents.removed.indexOf(childEnt) : -2;
             if ((ents.removed != null && (rix = ents.removed.indexOf(childEnt)) != -1) || !childEnt.isVisible(byLayer)) {
                treeNode.removeChildNode(childEnt);
                if (rix != -1)
                   ents.removed.remove(rix);
                continue;
             }

             TreeNode childTree = treeNode.findChildNodeForEnt(childEnt);
             if (childTree == null) {
                childTree = new TreeNode(childEnt);

                treeNode.children.add(pos, childTree);
             }
             addToIndex(childEnt, childTree, index);
             updatePackageContents(childEnt, (TreeNode) childTree, index, byLayer);
             pos++;
          }
       }
       if (ents.removed != null) {
          // TODO: no longer need this loop?
          for (TreeEnt rem:ents.removed) {
             treeNode.removeChildNode(rem);
          }
          ents.removed = null;
       }
   }

   // Keep an index of the visible nodes in the tree so we can do reverse selection - i.e. go from type name
   // to list of visible tree nodes that refer to it.
   void addToIndex(TreeEnt childEnt, TreeNode treeNode, Map<String,List<TreeNode>> index) {
      if (childEnt.isSelectable()) {
         List<TreeNode> l = index.get(childEnt.typeName);
         if (l == null) {
            l = new ArrayList<TreeNode>();

            if (childEnt.type != EntType.LayerDir)
               index.put(childEnt.typeName, l);

            if (childEnt.type == EntType.Package || childEnt.type == EntType.LayerDir)
               index.put(TypeTreeModel.PKG_INDEX_PREFIX + childEnt.value, l);
         }
         l.add(treeNode);
      }
   }
}
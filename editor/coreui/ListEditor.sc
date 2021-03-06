import sc.type.Type;
import java.util.Arrays;
import java.util.Collections;

/** Base class for both ListGridEditor and ListCellEditor */
class ListEditor extends InstanceEditor {
   List<Object> instList;
   List<Object> visList;
   Object componentType;
   String componentTypeName;
   int startIx;
   int countStartIx = 0;
   int maxNum;
   boolean componentIsValue = false;
   boolean externalSort = false;

   // Add filter criteria: Bool, enum, number string types: include/exclude flag, list of values, property-name
   // do we need a 'visible' rule or should they just add a new property initialized with that rule, then use the property filter on that column to filter

   ArrayList<String> sortProps = null;

   ListEditor(FormView view, TypeEditor parentEditor, Object parentProperty, Object type, Object inst, int listIx, InstanceWrapper wrapper, boolean instanceEditor) {
      super(view, parentEditor, parentProperty, type, inst, listIx, wrapper, instanceEditor);
      maxNum = EditorContext.MaxInstancesOfType;
      instList = convertInstToList(inst);
      componentType = resolveSrcTypeDeclaration(ModelUtil.getArrayOrListComponentType(type));
      componentTypeChanged();
      refreshVisibleList();
   }

   ListEditor(FormView view, TypeEditor parentEditor, Object compType, Object instList, boolean instanceEditor) {
      super(view, parentEditor, null, compType, instList, -1, null, instanceEditor);
      maxNum = EditorContext.MaxInstancesOfType;
      this.instList = convertInstToList(instList);
      componentType = compType;;
      componentTypeChanged();
      refreshVisibleList();
   }

   List<Object> convertInstToList(Object inst) {
      List<Object> res;
      if (inst instanceof List)
         res = (List) inst;
      else if (inst instanceof Object[])
         res = Arrays.asList((Object[]) inst);
      else if (inst != null)
         throw new IllegalArgumentException("Bad instance type to ListEditor");
      else
         res = null;
      return res;
   }

   Object findCommonBaseType(List<Object> insts, Object defaultType) {
      Object curType = null;
      for (int i = 0; i < insts.size(); i++) {
         Object inst = insts.get(i);
         if (inst != null) {
            Object instType = DynUtil.getType(inst);
            if (curType == null)
               curType = instType;
            else {
               if (!DynUtil.isAssignableFrom(curType, instType)) {
                  if (DynUtil.isAssignableFrom(instType, curType))
                     curType = instType;
                  else {
                     curType = DynUtil.findCommonSuperType(curType, instType);
                  }
               }
            }
         }
      }
      return curType == null ? defaultType : resolveSrcTypeDeclaration(curType);
   }

   @sc.obj.ManualGetSet // NOTE: get/set conversion not performed when this annotation is used
   void updateEditor(Object elem, Object prop, Object propType, Object inst, int ix, InstanceWrapper wrapper) {
      Object compType = resolveSrcTypeDeclaration(ModelUtil.getArrayOrListComponentType(propType));
      setTypeNoChange(prop, compType);
      componentType = compType;
      componentTypeChanged();
      updateListIndex(ix);
      this.instList = convertInstToList(inst);
      // Notify any bindings on 'instance' that the value is changed but don't validate those bindings before we've refreshed the children.
      Bind.sendInvalidate(this, "instList", inst);
      // This both invalidates and validates for type
      Bind.sendChange(this, "type", compType);
      refreshChildren();

      // Now we're ready to validate the bindings on the instance
      Bind.sendValidate(this, "instList", inst);
   }

   // Use this version for search results - when the value is not a property
   @sc.obj.ManualGetSet
   void updateListEditor(Object compType, List<Object> instList) {
      setTypeNoChange(null, compType);
      componentType = compType;
      componentTypeChanged();
      this.instList = instList;
      Bind.sendInvalidate(this, "instList", instList);
      // This both invalidates and validates for type
      Bind.sendChange(this, "type", compType);
      refreshChildren();

      // Now we're ready to validate the bindings on the instance
      Bind.sendValidate(this, "instList", instList);
   }

   @sc.obj.ManualGetSet
   void updateListInstance(List<Object> newList) {
      if (newList == this.instList)
         return;
      if (newList != null && instList != null) {
         if (newList.size() == instList.size()) {
            boolean sameList = true;
            for (int i = 0; i < newList.size(); i++) {
               if (newList.get(i) != instList.get(i)) {
                  sameList = false;
                  break;
               }
            }
            if (sameList)
               return;
         }
      }
      this.instList = newList;
      Bind.sendInvalidate(this, "instList", instList);
      refreshChildren();
      Bind.sendValidate(this, "instList", instList);
   }

   void componentTypeChanged() {
      List<Object> insts = instList;
      if (insts != null && insts.size() > 0) {
         componentType = findCommonBaseType(insts, componentType);
      }
      updateComponentTypeName();
      if (componentType != null && !(componentType instanceof BodyTypeDeclaration)) {
         if (componentType instanceof Class) {
            Type ct = Type.get((Class) componentType);
            if (ct != Type.Object) {
               componentIsValue = true;
            }
         }
         if (!componentIsValue) {
            editorModel.system.fetchRemoteTypeDeclaration(ModelUtil.getTypeName(componentType), new sc.type.IResponseListener() {
               void response(Object response) {
                  if (response instanceof BodyTypeDeclaration) {
                     componentType = response;
                  }
               }
               void error(int errorCode, Object error) {
                  System.err.println("*** Error response to fetchRemoteTypeDeclaration for componentType");
               }
            });
         }
      }
      updateProperties();
   }

   void updateListIndex(int ix) {
      listIndex = ix;
   }

   int getCurrentListSize() {
      return visList == null ? -1 : visList.size();
   }

   void updateComponentTypeName() {
      if (componentType != null && componentType != java.lang.Object.class)
         componentTypeName = ModelUtil.getTypeName(componentType);
   }

   String getFixedOperatorName() {
      return "list";
   }

   void refreshChildren() {
      refreshVisibleList();
   }

   void updateInstance() {
      updateInstList();
   }

   void updateInstList() {
      if (instance instanceof List && instance != instList)
         instList = (List) instance;
      else if (instance == null)
         instList = null;
   }

   // Instance change events where the instance itself has not changed might mean that the contents of the list have been changed
   void instanceChanged() {
      updateInstList();
      refreshVisibleList();
      super.instanceChanged();

      parentView.scheduleValidateTree();
   }

   // return 0 - no sort, -1 reverse, 1 forward
   int getSortDir(String propName) {
      if (sortProps == null)
         return 0;
      for (String sp:sortProps) {
         boolean reverse = sp.startsWith("-");
         if (reverse)
            sp = sp.substring(1);
         if (sp.equals(propName))
            return reverse ? -1 : 1;
      }
      return 0;
   }

   // val = 0 => remove sort, val = -1 reverse, val = 1 normal
   void updateSortDir(String propName, int val, boolean append) {
      boolean add = false;
      if (sortProps == null) {
         if (val == 0)
            return;
         sortProps = new ArrayList<String>();
         add = true;
      }
      else if (val == 0) {
         for (int i = 0; i < sortProps.size(); i++) {
            String sp = sortProps.get(i);
            boolean reverse = sp.startsWith("-");
            if (reverse)
               sp = sp.substring(1);
            if (sp.equals(propName)) {
               sortProps.remove(i);
               if (sortProps.size() == 0)
                  sortProps = null;
               break;
            }
         }
      }
      else {
         add = true;
      }
      if (add) {
         String sp;
         int i;
         for (i = 0; i < sortProps.size(); i++) {
            sp = sortProps.get(i);
            boolean reverse = sp.startsWith("-");
            if (reverse)
               sp = sp.substring(1);
            if (sp.equals(propName)) {
               String newSp = val == -1 ? "-" + propName : propName;
               sortProps.set(i, newSp);
               break;
            }
         }
         if (i == sortProps.size()) {
            if (!append && sortProps.size() > 0) {
               ArrayList<String> oldSortProps = new ArrayList<String>(sortProps);
               sortProps.clear();
               for (String oldSortProp:oldSortProps) {
                  if (oldSortProp.startsWith("-"))
                     oldSortProp = oldSortProp.substring(1);
                  refreshSortDir(oldSortProp);
               }
            }

            sortProps.add(val == -1 ? "-" + propName : propName);
         }
      }
      refreshVisibleList();
      sortChanged();
   }

   void sortChanged() {}

   List<Object> filterAndSort() {
      if (sortProps == null || externalSort)
         return instList;
      ArrayList<Object> res = new ArrayList<Object>(instList);
      java.util.Collections.sort(res, new java.util.Comparator() {
         int compare(Object o1, Object o2) {
            int res = 0;
            for (String sp:sortProps) {
               String propName = sp;
               int revMult;
               if (sp.startsWith("-")) {
                  propName = propName.substring(1);
                  revMult = -1;
               }
               else
                  revMult = 1;

               Object prop = getPropertyByName(propName);
               if (prop instanceof ComputedProperty) {
                  res = ((ComputedProperty) prop).compare(o1, o2) * revMult;
               }
               else {
                  Object pv1 = DynUtil.getPropertyValue(o1, propName);
                  Object pv2 = DynUtil.getPropertyValue(o2, propName);
                  if (pv1 == pv2)
                     res = 0;
                  else {
                     res = DynUtil.compare(pv1, pv2) * revMult;
                  }
               }
               if (res != 0)
                  return res;
            }
            return res;
         }
      });
      return res;
   }

   void refreshVisibleList() {
      oldLayer = editorModel == null ? null : editorModel.currentLayer;
      if (instList == null) {
         if (visList == null)
            visList = new ArrayList<Object>();
         else if (visList.size() > 0)
            visList.clear();
      }
      else {
         List<Object> inList = filterAndSort();
         int inSz = inList.size();
         int outSz = Math.min(inSz - startIx, maxNum);

         List<Object> newList;
         if (startIx != 0 || maxNum < inSz) {
            newList = new ArrayList<Object>(outSz);
            for (int i = 0; i < outSz; i++) {
               newList.add(inList.get(i + startIx));
            }
         }
         else
            newList = inList;
         if (visList == null) {
            visList = newList == instList ? new ArrayList<Object>(newList) : newList;
         }
         else {
            int oldSz = visList.size();
            for (int i = 0; i < outSz; i++) {
               Object newVal = newList.get(i);
               if (i >= oldSz)
                  visList.add(newVal);
               else if (visList.get(i) != newVal) {
                  visList.set(i, newVal);
               }
            }
            int toRemove = oldSz - outSz;
            while (toRemove > 0) {
               visList.remove(visList.size()-1);
               toRemove--;
            }
         }
      }
   }

   IElementEditor createElementEditor(Object listVal, int ix, IElementEditor oldTag, String displayMode) {
      Object propInst;
      Object propType;
      BodyTypeDeclaration innerType = null;

      if (displayMode != null && displayMode.equals("header")) {
         // For the header, the listVal is really the property
         return super.createElementEditor(listVal, ix, oldTag, displayMode);
      }

      Object compType = componentType;
      if (!componentIsValue) {
         // Use the type of the list value if we have a src type for it because that will let us edit the
         // initializers of the actual instance type. Component type starts out as the common super class of
         // all types in the grid.
         if (listVal != null) {
            Object newCompType = DynUtil.getType(listVal);
            newCompType = ModelUtil.resolveSrcTypeDeclaration(editorModel.system, newCompType);
            if (newCompType instanceof BodyTypeDeclaration || !(compType instanceof BodyTypeDeclaration))
               compType = newCompType;
         }
         compType = ModelUtil.resolveSrcTypeDeclaration(editorModel.system, compType);
      }

      listVal = convertEditorInst(listVal);

      String editorType = getEditorType(parentProperty, compType, listVal, instanceMode || instanceEditor);
      Object editorClass = getEditorClass(editorType, displayMode);

      int listIx = ix + startIx;
      Object oldClass = oldTag != null ? DynUtil.getType(oldTag) : null;
      if (oldClass == editorClass) {
         IElementEditor oldEditor = (IElementEditor) oldTag;
         oldEditor.updateEditor(null, null, compType, listVal, listIx, null);
         oldEditor.setListIndex(listIx);
         return oldTag;
      }
      else {
         IElementEditor newEditor = (IElementEditor) DynUtil.newInnerInstance(editorClass, null, null, parentView, ListEditor.this, null, compType, listVal, listIx, null, instanceEditor);
         newEditor.setElemToEdit(compType);
         newEditor.setRepeatIndex(ix);
         return newEditor;
      }
   }

   void gotoComponentType() {
      if (componentTypeName != null)
         editorModel.changeCurrentType(ModelUtil.findType(editorModel.system, componentTypeName), null, null);
   }

   String getEditorType() {
      return "list";
   }

   void refreshSortDir(String propName) {}

   void refreshListIndex() {
      if (childViews != null) {
         for (IElementEditor view:childViews) {
            if (view instanceof RowEditor) {
               ((RowEditor) view).validateListIndex();
            }
         }
      }
   }
}

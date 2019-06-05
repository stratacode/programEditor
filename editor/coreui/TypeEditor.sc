import java.util.Arrays;

@Component
abstract class TypeEditor extends CompositeEditor implements sc.type.IResponseListener {
   @sc.obj.GetSet
   FormView parentView;  // The root view for this editor
   @sc.obj.GetSet
   TypeEditor parentEditor; // The parent editor if this editor is parent of a hierarchy.
   InstanceEditor formEditor; // If the parent is an instance editor
   Object parentProperty;  // If this editor is defined from a property in the parent editor - otherwise, it's null
   Object parentPropertyType;
   ListEditor parentList; // If this editor is defined inside of a list

   UIIcon icon := type == null ? null : GlobalResources.lookupUIIcon(type);

   int numCols = 1;
   int numRows = 1;

   boolean removed = false;

   Object type;
   ClientTypeDeclaration clientType := ModelUtil.getClientTypeDeclaration(type);;

   EditorModel editorModel;
   Layer classViewLayer = editorModel.currentLayer;  // Gets set to the current layer when we are created

   Layer oldLayer = null; // layer the last time were updated
   boolean oldMergeLayers, oldInherit;

   // Updatable on client and server so no need to sync it explicitly
   @sc.obj.Sync(syncMode=sc.obj.SyncMode.Disabled)
   Object[] properties;

   String operatorName;
   String extTypeName;
   String displayName;

   List<IElementEditor> childViews;

   //String title;

   int nestLevel = 0;

   @Bindable
   boolean cellMode = false;

   boolean rowMode = false;

   type =: typeChanged();

   @Bindable
   int listIndex; // If we are in a list component, the original absolute index of our element

   TypeEditor(FormView view, TypeEditor parentEditor, Object parentProperty, Object type, Object inst, int listIx, InstanceWrapper wrapper) {
      parentView = view;
      this.editorModel = parentView.editorModel;
      this.parentEditor = parentEditor;
      if (parentEditor instanceof ListEditor)
         this.parentList = (ListEditor) parentEditor;
      if (parentEditor instanceof InstanceEditor)
         this.formEditor = (InstanceEditor) parentEditor;
      updateComputedValues();
      this.setTypeNoChange(parentProperty, type);
      if (parentEditor != null)
         this.nestLevel = parentEditor.nestLevel + 1;

      this.listIndex = listIx;
      /* TODO: ideally would like to optimize the case where there's a common type for all elements in the list
      if (parentList != null) {
         this.properties = parentList.properties;
      }
      */
   }

   void updateComputedValues() {
   }

   Object resolveSrcTypeDeclaration(Object type) {
      if (!(type instanceof BodyTypeDeclaration) && type != null && editorModel != null)
         type = ModelUtil.resolveSrcTypeDeclaration(editorModel.system, type);
      return type;
   }

   void init() {
      // We set type in the constructor with no change event, so there's no event on init.  typeChanged() won't be called even in the sendChange event below because it's being changed to the same value.
      // but some other bindings rely on this event - such as the one inside of instanceList which is created before the type has been set (because of a call to getBorder() inside of swing).
      typeChanged();
      Bind.sendChange(this, "type", type);
      Bind.sendChange(this, "parentProperty", parentProperty);
   }

   abstract void refreshChildren();

   void rebuildChildren() {
      clearChildren();
      refreshChildren();
   }

   String getFixedOperatorName() {
      return null;
   }

   void operatorChanged() {
      operatorName = getFixedOperatorName();
      if (operatorName == null) {
         if (type == null)
            operatorName = null;
         else if (parentProperty != null)
            operatorName = "property";
         else if (ModelUtil.isEnumType(type))
            operatorName = "enum";
         else if (ModelUtil.isEnum(type))
            operatorName = "enum constant";
         else if (ModelUtil.isInterface(type))
            operatorName = "interface";
         else if (ModelUtil.isLayerType(type))
            operatorName = "layer";
         else if (ModelUtil.isObjectType(type))
            operatorName = "object";
         else
            operatorName = "class";
      }
   }

   void typeChanged() {
      operatorChanged();

      if (parentProperty != null)
         displayName = propertyName;
      else if (type != null)
         displayName = editorModel.getClassDisplayName(type);
      else
         displayName = "...unknown...";

      if (type != null) {
         extTypeName = parentProperty == null ? ModelUtil.getExtendsTypeName(type) : ModelUtil.getTypeName(type);
         //title = operatorName + " " + ModelUtil.getClassName(type) + (extTypeName == null ? "" : " extends " + CTypeUtil.getClassName(extTypeName));
         Object[] newProps = editorModel.getPropertiesForType(type);
         Boolean includeStatic = (Boolean) ModelUtil.getAnnotationValue(type, "sc.obj.EditorSettings", "includeStatic");
         if (includeStatic == null)
            includeStatic = false;
         ArrayList<Object> visProps = new ArrayList<Object>();
         Layer currentLayer = editorModel.currentLayer;
         addComputedProperties(visProps, newProps);
         if (newProps != null) {
            for (Object prop:newProps) {
                // TODO: this method is defined in modelImpl which is not accessible to coreui.  Maybe add default implementations to the model class?
                if (editorModel.filteredProperty(type, prop, false, instanceMode))
                    continue;

                if (!editorModel.isVisible(prop))
                   continue;

                if (prop instanceof BodyTypeDeclaration) {
                   prop = editorModel.processVisibleType(prop);
                   if (prop == null)
                        continue;
                }
                else if (ModelUtil.isProperty(prop)) {
                   if (!includeStatic && ModelUtil.hasModifier(prop, "static"))
                      continue;
                   // If this type is not already a property in this layer, and there is a current layer set and this object is not defined in that layer (or merged), skip it.
                   // If we get a def for a prop which is after the specified layer, see if there's a previous definition
                   // we should be using first.
                   if (parentProperty == null && currentLayer != null) {
                      Layer propLayer = ModelUtil.getPropertyLayer(prop);

                      // If this property is not defined or modified in this layer or one which should be merged continue.
                      while (propLayer != null && !editorModel.currentLayerMatches(propLayer)) {
                         Object prevProp = ModelUtil.getPreviousDefinition(prop);
                         if (prevProp == null) {
                            prop = null;
                            break;
                         }
                         prop = prevProp;
                         propLayer = ModelUtil.getPropertyLayer(prop);
                         if (propLayer == null)
                            prop = null;
                      }
                      if (prop == null)
                         continue;
                   }
                }
                else // class or a compiled type we can't handle
                   continue;
                visProps.add(prop);
            }
         }
         properties = visProps.toArray();
         oldLayer = currentLayer;
         oldMergeLayers = editorModel.mergeLayers;
         oldInherit = editorModel.inherit;
      }
      else {
         extTypeName = null;
         properties = null;
         oldLayer = null;
      }

      // In case we were initialized with a compiled type on the client, check and see if there's a src type on the server.
      // We should already have tried to call resolveSrcDeclaration before making the fetch call
      if (!(type instanceof BodyTypeDeclaration)) {
         editorModel.system.fetchRemoteTypeDeclaration(ModelUtil.getTypeName(type), this);
      }
   }

   void addComputedProperties(List<Object> retProps, Object[] allProps) {
   }

   // Sets the field without firing a change event
   @sc.obj.ManualGetSet
   void setTypeNoChange(Object parentProp, Object newType) {
      parentProperty = parentProp;
      if (parentProp != null)
         parentPropertyType = EditorModel.getPropertyType(parentProp);
      newType = resolveSrcTypeDeclaration(newType);
      type = newType;
   }

   void removeListeners() {
      if (childViews != null) {
         for (IElementEditor view:childViews)
            view.removeListeners();
      }
   }

   void clearChildren() {
      removeListeners();
      if (childViews != null)
          childViews.clear();
   }

   void updateListeners(boolean add) {
      if (childViews != null) {
         for (IElementEditor view:childViews)
            view.updateListeners(add);
      }
   }

   void stop() {
      clearChildren();
      setVisible(false);
   }

   // Called when the parent is a FormView representing a specific instance.  If we are storing a child instance
   // we can update our instance to the correct child.
   void parentInstanceChanged(Object parentInst) {
   }

   public String getIconPath() {
      return icon == null ? "" : icon.path;
   }

   public String getIconAlt() {
      return icon == null ? "" : icon.desc;
   }

   public String getPropertyName() {
      return editorModel.getPropertyName(parentProperty);
   }

   boolean getInstanceMode() {
      if (parentView == null) {
         return true;
      }
      return parentView.instanceMode;
   }

   String getEditorType(Object prop, Object propType, Object propInst, boolean instMode) {
      // When editing the type, things are simple - it's just forms for sub-types and text fields for properties for the init expr
      if (!instMode) {
         if (prop != null)
            return "text";
         else
            return "form";
      }

      if (prop instanceof CustomProperty) {
         String custType = ((CustomProperty) prop).editorType;
         if (custType != null)
            return custType;
      }

      Object instType = propInst == null ? null : DynUtil.getType(propInst);
      String editorType = instType == null ? null : (String) ModelUtil.getAnnotationValue(instType, "sc.obj.EditorSettings", "editorType");

      if (editorType == null && propType != null)
          editorType = (String) ModelUtil.getAnnotationValue(propType, "sc.obj.EditorSettings", "editorType");
      if (editorType == null) {
         if (prop != null && propType != null) {
            if (ModelUtil.isEnumType(propType) || (instType != null && ModelUtil.isEnumType(instType))) {
               editorType = "choice";
            }
            // If the type is a string, number or if the type is Object we choose the text editor if the instance is a string or number.  This happens for tag objects where we use 'Object' as the type
            // of a property that turns into a string.  I'm not sure why we can't use String type there but remember it caused problems.
            else if ((propType instanceof Class && (PTypeUtil.isANumber((Class) propType) || PTypeUtil.isStringOrChar((Class)propType))) ||
                      propInst instanceof String || propInst instanceof Number || propInst instanceof StringBuilder) {
               if (propInst == null || !(propInst instanceof String) || ((String) propInst).indexOf('\n') == -1) {
                 editorType = "text";
               }
               else {
                 editorType = "textArea";
               }
            }
            else if (DynUtil.isAssignableFrom(List.class, propType) || DynUtil.isArray(propType) || (propInst != null && (propInst instanceof List || propInst instanceof Object[]))) {
               editorType = "list";
            }
            else if (propType == Boolean.class || propType == Boolean.TYPE) {
               editorType = "toggle";
            }
            else if ((propInst == null || !DynUtil.isObject(propInst)) && (!editorModel.isReferenceType(propType) || (instType == null || !editorModel.isReferenceType(instType)))) {
               editorType = "form";
            }
            else
               editorType = "ref";
         }
         else {
            editorType = "form";
         }
      }
      return editorType;
   }

   Object convertEditorInst(Object inst) {
      if (inst instanceof Object[])
         return new ArrayList<Object>(Arrays.asList((Object[])inst));
      return inst;
   }

   Object getEditorClass(String editorType, String displayMode) {
      if (editorType.equals("text"))
         return TextFieldEditor.class;
      else if (editorType.equals("textArea"))
         return MultiLineTextEditor.class;
      else if (editorType.equals("ref"))
         return ReferenceFieldEditor.class;
      else if (editorType.equals("toggle"))
         return ToggleFieldEditor.class;
      else if (editorType.equals("choice"))
         return ChoiceFieldEditor.class;
      else if (editorType.equals("form"))
         return FormEditor.class;
      else if (editorType.equals("list"))
         return ListEditor.class;
      System.err.println("*** Unrecognized editorType: " + editorType);
      return TextFieldEditor.class;
   }

   // If the layer has changed since this editor was last rebuilt, it might need to be rebuilt because the properties have changed
   void invalidateEditor() {
      if (editorModel != null &&
          (oldLayer != null && oldLayer != editorModel.currentLayer) ||
          (oldMergeLayers != editorModel.mergeLayers) ||
          (oldInherit != editorModel.inherit)) {
         clearChildren();
         typeChanged();
         refreshChildren();
      }
   }

   // In response to fetchRemoteTypeDeclaration, we implement the IResponseListener interface
   void response(Object resp) {
      if (resp instanceof BodyTypeDeclaration) {
         type = resp;
      }
      else
         System.err.println("*** Unrecognized response to fetchRemoteTypeDeclaration");
   }
   void error(int code, Object error) {
      System.err.println("*** Error response to fetchRemoteTypeDeclaration");
   }

   int getDefaultCellWidth(String editorType, Object prop) {
      if (editorType.equals("text") || editorType.equals("textArea") || editorType.equals("list"))
         return 400;
      else if (editorType.equals("ref") || editorType.equals("form") || editorType.equals("choice"))
         return 200;
      else if (editorType.equals("toggle"))
         return 200;
      return 100;
   }

   int getDefaultCellHeight(String editorType, Object prop) {
      return 30;
   }

   abstract String getEditorType();

   int getCellWidth() {
      if (cellMode) {
         int width = formEditor.getExplicitWidth(listIndex);
         if (width != -1)
            return width;
         return formEditor.getDefaultCellWidth(editorType, null);
      }
      return -1;
   }

   int getCellHeight() {
      if (cellMode || rowMode) {
         int height = formEditor.getExplicitHeight(listIndex);
         if (height != -1)
            return height;
         return formEditor.getDefaultCellHeight(editorType, null);
      }
      return -1;
   }

   void scheduleValidateTree() {
      if (parentView != null)
         parentView.scheduleValidateTree();
   }

   void validateSize() {
      if (childViews != null) {
         for (IElementEditor view:childViews)
            view.validateSize();
      }
   }
}

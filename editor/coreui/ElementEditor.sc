import sc.lang.java.ModelUtil;
import sc.lang.java.VariableDefinition;

@Component
@CompilerSettings(propagateConstructor="sc.editor.FormView,sc.editor.InstanceEditor,Object,Object,Object,int,sc.lang.InstanceWrapper")
abstract class ElementEditor extends PrimitiveEditor implements sc.obj.IStoppable {
   InstanceEditor formEditor;
   Object propC;
   String propertySuffix = "";
   String propertyName := getPropertyNameString(propC);
   EditorModel editorModel;

   @Bindable
   int listIndex;

   IVariableInitializer varInit := propC instanceof IVariableInitializer ? (IVariableInitializer) propC : null;
   UIIcon icon;
   String errorText;
   String oldPropName;
   int changeCt = 0;
   Object oldListenerInstance = null;
   Object currentValue := getPropertyValue(propC, formEditor.instance, changeCt, instanceMode);
   String currentStringValue := currentValue == null ? "" : String.valueOf(currentValue); // WARNING: using currentValue.toString() does not work because data binding has a bug and won't listen for changes on 'currentValue' in that case
   Object propType;

   boolean instanceMode;
   boolean constantProperty;
   boolean editable := !instanceMode || !constantProperty;
   boolean rowStart;

   // Placeholders for the ability to allow the user to set the width/height of a component
   int explicitWidth = -1, explicitHeight = -1;

   @Bindable
   boolean cellMode = false;

   ElementEditor(FormView parentView, InstanceEditor formEditor, Object prop, Object propType, Object propInst, int listIx, InstanceWrapper wrapper) {
      instanceMode = formEditor.instanceMode;
      this.formEditor = formEditor;
      editorModel = parentView.editorModel;
      this.propC = prop;
      if (instanceMode)
          this.currentValue = propInst;
      this.propType = propType;
      this.listIndex = listIx;
   }

   void init() {
      updateComputedValues();
   }

   void updateComputedValues() {
      rowStart = listIndex == 0 && cellMode;
      varInit = propC instanceof IVariableInitializer ? (IVariableInitializer) propC : null;
      visible = getPropVisible();
      constantProperty = EditorModel.isConstantProperty(propC);
      icon = propC == null ? null : GlobalResources.lookupUIIcon(propC, ModelUtil.isDynamicType(formEditor.type));
   }

   public BaseView getParentView() {
      return formEditor == null ? null : formEditor.parentView;
   }

   String getPropertyNameString(Object prop) {
      return prop == null ? "<null>" : EditorModel.getPropertyName(prop);
   }

   String getOperatorDisplayStr(Object instance, IVariableInitializer varInit) {
      return !instanceMode && instance == null && varInit != null ? (varInit.operatorStr == null ? " = " : varInit.operatorStr) : "";
   }

   void updateEditor(Object elem, Object prop, Object propType, Object inst, int ix, InstanceWrapper wrapper) {
      propC = elem;
      // setElemToEdit(elem); TODO - do we need this call?
      this.listIndex = ix;
      updateComputedValues();
   }

   boolean getPropVisible() {
      if (propC instanceof CustomProperty)
         return true;
      // Hide reverse only bindings when displaying instance since they are not settable
      return varInit != null && (formEditor.instance == null || !DynUtil.equalObjects(varInit.operatorStr, "=:"));
   }

   static boolean isIndexedProperty(Object prop) {
      return prop instanceof VariableDefinition && ((VariableDefinition) prop).indexedProperty;
   }

   object valueEventListener extends AbstractListener {
      public boolean valueValidated(Object obj, Object prop, Object eventDetail, boolean apply) {
         changeCt++; // Sends a change event so getPropertyStringValue is called again to update the changed value.
         return true;
      }
   }

   // Using these values as parameters so we get change events for them
   static Object getPropertyValue(Object prop, Object instance, int changeCt, boolean instanceMode) {
      if (prop == null)
         return null;
      if (prop instanceof CustomProperty)
         return ((CustomProperty) prop).value;
      if (prop instanceof IVariableInitializer) {
         IVariableInitializer varInit = (IVariableInitializer) prop;

         if (instance == null)
            return !instanceMode ? varInit.initializerExprStr == null ? "" : varInit.initializerExprStr : null;
         else if (!isIndexedProperty(prop)) {
            Object val = DynUtil.getPropertyValue(instance, varInit.variableName);
            if (val == null)
               return null;
            return val;
         }
         else
            return null;
      }
      else if (prop instanceof String)
         return DynUtil.getPropertyValue(instance, (String)prop);
      else if (prop instanceof sc.type.IBeanMapper)
         return DynUtil.getPropertyValue(instance, ((sc.type.IBeanMapper) prop).getPropertyName());
      return null;
   }

   /** Part of the IStoppable implementation */
   void stop() {
      removeListeners(); // Dispose has already been called at this point
      visible = false;
   }

   private String getVariableName() {
      return varInit == null ? null : varInit.variableName;
   }

   private String getSimplePropName() {
      String propName = getVariableName();
      String simpleProp;
      if (propName != null) {
         int ix = propName.indexOf("[");
         if (ix == -1)
            simpleProp = propName;
         else
            simpleProp = propName.substring(0, ix);
      }
      else
         simpleProp = propName;

      return simpleProp;
   }

   abstract Object getElementValue();

   void updateListeners(boolean add) {
      String simpleProp = getSimplePropName();
      String propName = getVariableName();

      if (oldListenerInstance == formEditor.instance && propName == oldPropName)
         return;

      removeListeners();

      if (add && propName != null && !propName.equals("<null>")) {
         if (formEditor.instance != null) {
            Bind.addDynamicListener(formEditor.instance, formEditor.type, simpleProp, valueEventListener, IListener.VALUE_CHANGED);
            oldListenerInstance = formEditor.instance;
         }
      }
      oldPropName = propName;
   }

   void removeListeners() {
      if (oldPropName != null && !oldPropName.equals("<null>") && !(propC instanceof CustomProperty)) {
         if (oldListenerInstance != null) {
            Bind.removeDynamicListener(oldListenerInstance, formEditor.type, getSimplePropName(), valueEventListener, IListener.VALUE_CHANGED);
            oldListenerInstance = null;
         }
      }
   }

   void invalidateEditor() {
   }

   String getIconPath() {
      return icon == null ? "" : icon.path;
   }

   String getIconAlt() {
      return icon == null ? "" : icon.desc;
   }

   String getEditorType() {
      return formEditor.getEditorType(propC, propType, currentValue, instanceMode);
   }

   int getCellWidth() {
      if (formEditor == null)
         return 0;
      int width = formEditor.getExplicitWidth(listIndex);
      if (width != -1)
         return width;
      if (propC instanceof CustomProperty)
         return ((CustomProperty) propC).defaultWidth;
      return formEditor.getDefaultCellWidth(editorType, propC);
   }

   int getCellHeight() {
      if (formEditor == null)
         return 0;
      //int height = formEditor.getExplicitHeight(listIndex);
      int height = explicitHeight;
      if (height != -1)
         return height;
      return formEditor.getDefaultCellHeight(editorType, propC);
   }

   void sizeChanged() {
      formEditor.scheduleValidateTree();
   }

   void validateEditorTree() {
   }

   void validateSize() {
   }
}


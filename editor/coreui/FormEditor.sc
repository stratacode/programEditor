class FormEditor extends TypeEditor {
   Object instance;
   Object oldInstance;
   FormView parentFormView;

   instance =: instanceChanged();

   FormEditor(FormView view, TypeEditor parentEditor, BodyTypeDeclaration type, Object instance) {
      super(view, parentEditor, type, instance);
      parentFormView = view;

      this.instance = instance;
   }

   void init() {
      // Bindings not set before we set the instance property so need to do this once up front
      super.init();
      instanceChanged();
   }

   void instanceChanged() {
      if (removed)
          return;
      if (instance != oldInstance) {
         updateListeners();
         updateChildInsts();
         oldInstance = instance;
      }
   }

   void updateChildInsts() {
      for (IElementEditor view:childViews) {
        if (view instanceof TypeEditor) {
           TypeEditor te = ((TypeEditor) view);
           te.parentInstanceChanged(instance);
        }
      }
   }

   void parentInstanceChanged(Object parentInst) {
       if (parentInst == null)
          instance = null;
       else {
          instance = DynUtil.getPropertyPath(parentInst, CTypeUtil.getClassName(ModelUtil.getInnerTypeName(type)));
       }
   }
}
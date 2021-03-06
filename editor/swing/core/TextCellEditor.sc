import sc.type.RTypeUtil;

// view types: text field view {int, string}, enum, sub-object, list/array
TextCellEditor {
   formComponent = textField;

   object textField extends CompletionTextField {
      location := SwingUtil.point(TextCellEditor.this.x, TextCellEditor.this.y);
      size := SwingUtil.dimension(TextCellEditor.this.width, TextCellEditor.this.height);

      border = createCellBorder();

      completionProvider {
         ctx := editorModel.ctx;
      }

      // If we have a fromString converter registered, we can edit this guy in value mode
      boolean settable := formEditor.instance == null || EditorModel.isSettableFromString(propC, propType);
      foreground := settable ? ComponentStyle.defaultForeground : SwingUtil.averageColors(ComponentStyle.defaultForeground, ComponentStyle.defaultBackground);

      // instance could be retrieved through type hierarchy but we need to update the binding when the instance changes
      enteredText := propertyValueString(formEditor.instance, propC, changeCt);

      editable := TextCellEditor.this.editable;

      // Only trigger the change to the model when the user enters the text.  Not when we set enteredText because the value changed
      userEnteredCount =: settable ? setElementValue(formEditor.type, formEditor.instance, propC, enteredText) : errorLabel.text = "This view shows the toString output of property: " + propertyName + " No string conversion for type: " ;

      focus =: focusChanged(this, focus);
   }

   String setElementValue(Object type, Object inst, Object val, String text) {
      if (type == null || val == null)
         return "No type or value";
      String error = null;
      try {
         if (type instanceof ClientTypeDeclaration)
            type = ((ClientTypeDeclaration) type).getOriginal();

         error = super.setElementValue(type, inst, val, text);
         textField.enteredText = propertyValueString(formEditor.instance, propC, changeCt);
      }
      catch (RuntimeException exc) {
         error = exc.getMessage();
         displayFormError(error);
      }
      finally {
      }
      return error;
   }

   String getElementStringValue() {
      return textField.text;
   }

}

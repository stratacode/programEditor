abstract class ToggleElementEditor extends ElementEditor {
   boolean checkedValue := currentValue instanceof Boolean && ((Boolean) currentValue);
   checkedValue =: currentValue = checkedValue;
}

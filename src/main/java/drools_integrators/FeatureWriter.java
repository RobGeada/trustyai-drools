package drools_integrators;

import org.apache.commons.beanutils.ConvertingWrapDynaBean;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class FeatureWriter {
    Method method;
    ConvertingWrapDynaBean convertingWrapDynaBean;
    String fieldName;
    Object argument;

    public FeatureWriter(Method method, ConvertingWrapDynaBean convertingWrapDynaBean, String fieldName, Object argument){
        this.method = method;
        this.convertingWrapDynaBean = convertingWrapDynaBean;
        this.fieldName = fieldName;
        this.argument = argument;
    }

    @Override
    public String toString() {
        return String.format(method.toString());
    }

    public Object invoke(String newValue) throws InvocationTargetException, IllegalAccessException {
        return this.method.invoke(this.convertingWrapDynaBean, this.fieldName, newValue);
    }
}

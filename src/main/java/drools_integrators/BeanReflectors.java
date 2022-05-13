package drools_integrators;

import org.apache.commons.beanutils.ConvertingWrapDynaBean;
import org.apache.commons.beanutils.DynaClass;
import org.apache.commons.beanutils.DynaProperty;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BeanReflectors {

    private BeanReflectors(){ throw new IllegalStateException("Utility class");}


    // extract all gettable fields from object recursively into dictionary of field_name:Object
    public static Map<String, Object> beanProperties(final Object bean, RuleFireListener ruleTracker) {
        return beanProperties(bean, ruleTracker, "", false);
    }

    // extract all gettable fields from object recursively into dictionary of field_name:Object
    public static Map<String, Object> beanProperties(final Object bean, RuleFireListener ruleTracker, String prefix, boolean verbose) {
        final HashMap<String, Object> result = new HashMap<>();
        String name = prefix.equals("") ? bean.getClass().getName() : prefix;

        // check if object itself is a "base" type
        if (bean instanceof Number || bean instanceof String || bean instanceof Boolean){
            if (verbose) {System.out.printf("\t %s=%s, primitive? %b", name, bean, true);}
            result.put(name, bean);
            if (verbose){System.out.println("...adding to result");}
            return result;
        }

        // otherwise investigate its contents
        if (verbose){ System.out.println("Exploring "+ name);}
        PropertyDescriptor[] propertyDescriptors = new PropertyDescriptor[0];
        try {
            propertyDescriptors = Introspector.getBeanInfo(bean.getClass(), Object.class).getPropertyDescriptors();
        } catch (Exception ex) {
            // ignore, no property descriptors
        }
        for (PropertyDescriptor propertyDescriptor : propertyDescriptors) {
            final Method readMethod = propertyDescriptor.getReadMethod();
            // if there's getters:
            if (readMethod != null) {
                Object read = null;
                try {
                    read = readMethod.invoke(bean, (Object[]) null);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    //ignore, non-readable read method
                }
                if (read == null){ continue; }

                String thisName = name + "." + propertyDescriptor.getName();
                boolean inContainers = true;
                if (ruleTracker != null) {
                    if (ruleTracker.getIncludedOutputContainers().size() > 0) {
                        inContainers = ruleTracker.getIncludedOutputContainers().stream().anyMatch(propertyDescriptor.getName()::contains);
                    }
                    if (ruleTracker.getExcludedOutputContainers().size() > 0) {
                        inContainers &= ruleTracker.getExcludedOutputContainers().stream().noneMatch(propertyDescriptor.getName()::contains);
                    }
                }

                if (verbose) {
                    System.out.printf("\t %s=%s, primitive? %b, %s in Containers? %b",
                            thisName, read,
                            (read instanceof Number || read instanceof String || read instanceof Boolean),
                            propertyDescriptor.getName(), inContainers);
                }

                if (ruleTracker != null) {
                    if (inContainers) {
                        ruleTracker.getActualIncludedContainers().add(thisName);
                    } else {
                        ruleTracker.getActualExcludedContainers().add(thisName);
                    }
                }

                // if the get'ted object is a 'base' type:
                if ((read instanceof Number || read instanceof String || read instanceof Boolean) && inContainers) {
                    result.put(thisName, read);
                    if (verbose) {
                        System.out.println("...adding to result");
                    }
                } else if (read instanceof Iterable && inContainers) { //is is an iterable object?
                    int i = 0;
                    if (verbose) {
                        System.out.printf("%n=== recursing %s ======================%n", name);
                    }
                    for (Object o : (Iterable<?>) read) {
                        beanProperties(
                                o,
                                ruleTracker,
                                thisName + "[" + i + "]",
                                verbose)
                                .forEach(result::putIfAbsent);
                        i++;
                    }
                    if (verbose) {
                        System.out.println("=== end recursion ==================================\n");
                    }
                } else if (inContainers) { // if the object is not base or iterable, but is a specified container:
                    if (verbose) {
                        System.out.println("...unpacking ======================");
                    }
                    beanProperties(
                            read,
                            ruleTracker,
                            thisName,
                            verbose)
                            .forEach(result::putIfAbsent);
                    if (verbose) {
                        System.out.println("=== end unpack ==================================");
                    }
                }
            }
        }

        return result;
    }


    // extract all writable fields from object recursively into dictionary of field_name:FeatureWriter
    public static Map<String, FeatureWriter> beanWriteProperties(final Object bean, boolean verbose) {
        return beanWriteProperties(bean, "", verbose);
    }

    // extract all writable fields from object recursively into dictionary of field_name:FeatureWriter
    public static Map<String, FeatureWriter> beanWriteProperties(final Object bean, String prefix, boolean verbose) {
        return beanWriteProperties(bean, prefix, verbose, "");
    }

    // extract all gettable fields from object recursively into dictionary of field_name:object
    public static Map<String, FeatureWriter> beanWriteProperties(final Object bean, String prefix, boolean verbose, String verbosePrefix) {
        final HashMap<String, FeatureWriter> result = new HashMap<>();
        String name = prefix.equals("") ? bean.getClass().getName() : prefix;

        // check if object itself is a "base" type
        if (bean instanceof Number || bean instanceof String || bean instanceof Boolean){
            if (verbose) {System.out.printf("\t %s=%s, primitive? %b", name, bean, true);}
            return result;
        }

        // otherwise investigate its contents
        if (verbose){ System.out.printf("%sExploring %s:%n", verbosePrefix, name);}
        ConvertingWrapDynaBean convertingWrapDynaBean = new ConvertingWrapDynaBean(bean);
        DynaClass dynaClass = convertingWrapDynaBean.getDynaClass();
        for (DynaProperty dynaProperty : dynaClass.getDynaProperties()){
            Method writeMethod = null;
            Object read = null;
            try {
                writeMethod = convertingWrapDynaBean.getClass().getMethod("set", String.class, Object.class);
                read = convertingWrapDynaBean.get(dynaProperty.getName());
                writeMethod.invoke(convertingWrapDynaBean, dynaProperty.getName(), read);
            } catch (Exception ex) {
                //ignore non-readable read method or non-writeable write
                if (verbose) {ex.printStackTrace();}
            }
            if (read == null || writeMethod ==null) {
                continue;
            }
            String thisName = name + "." + dynaProperty.getName();
            if (verbose) {
                System.out.printf("%s\t %s=%s, primitive? %b",
                        verbosePrefix,
                        thisName, read.toString(),
                        (read instanceof Number || read instanceof String || read instanceof Boolean));
            }
            // if the get'ted object is a 'base' type:
            if ((read instanceof Number || read instanceof String || read instanceof Boolean)) {
                result.put(thisName, new FeatureWriter(writeMethod, convertingWrapDynaBean, dynaProperty.getName(), read));
                if (verbose) {
                    System.out.println("...adding to result");
                }
            } else if (read instanceof Iterable) { //is is an iterable object?
                int i = 0;
                if (verbose) {
                    System.out.printf("%n%s\t=== recursing %s ======================%n", verbosePrefix, thisName);
                }
                for (Object o : (Iterable<?>) read) {
                    beanWriteProperties(
                            o,
                            thisName + "[" + i + "]",
                            verbose, verbosePrefix + "\t")
                            .forEach(result::putIfAbsent);
                    i++;
                }
                if (verbose) {
                    System.out.printf("%s\t=== end recursion ==================================%n", verbosePrefix);
                }
            } else { // if the object is not base or iterable, but is a specified container:
                if (verbose) {
                    System.out.printf("%n%s\t=== unpacking %s ==================================%n", verbosePrefix, thisName);
                }
                beanWriteProperties(
                        read,
                        thisName,
                        verbose,
                        verbosePrefix + "\t")
                        .forEach(result::putIfAbsent);
                if (verbose) {
                    System.out.printf("%s\t=== end unpack ==================================%n", verbosePrefix);
                }
            }
        }

        return result;
    }

    // extract all non-primitive objects from object recursively into List of Objects
    public static List<Object> beanContainers(final Object bean, String prefix, boolean verbose, String verbosePrefix) {
        final List<Object> result = new ArrayList<>(List.of(bean));
        String name = prefix.equals("") ? bean.getClass().getName() : prefix;

        // check if object itself is a "base" type
        if (bean instanceof Number || bean instanceof String || bean instanceof Boolean){
            if (verbose) {System.out.printf("\t %s=%s, primitive? %b", name, bean, true);}
            return result;
        }

        // otherwise investigate its contents
        if (verbose){ System.out.printf("%sExploring %s:%n", verbosePrefix, name);}
        ConvertingWrapDynaBean convertingWrapDynaBean = new ConvertingWrapDynaBean(bean);
        DynaClass dynaClass = convertingWrapDynaBean.getDynaClass();
        for (DynaProperty dynaProperty : dynaClass.getDynaProperties()){
            Object read = null;
            try {
                read = convertingWrapDynaBean.get(dynaProperty.getName());
            } catch (Exception ex) {
                //ignore non-readable read method or non-writeable write
                if (verbose) {ex.printStackTrace();}
            }
            if (read == null) {
                continue;
            }
            String thisName = name + "." + dynaProperty.getName();
            if (verbose) {
                System.out.printf("%s\t %s=%s, primitive? %b",
                        verbosePrefix,
                        thisName, read.toString(),
                        (read instanceof Number || read instanceof String || read instanceof Boolean),
                        dynaProperty.getName());
            }
            // if the get'ted object is a 'base' type:
            if ((read instanceof Number || read instanceof String || read instanceof Boolean)) {
                result.add(read);
                if (verbose) {
                    System.out.println("...adding to result");
                }
            } else if (read instanceof Iterable) { //is is an iterable object?
                int i = 0;
                if (verbose) {
                    System.out.printf("%n%s\t=== recursing %s ======================%n", verbosePrefix, thisName);
                }
                for (Object o : (Iterable<?>) read) {
                    result.add(bean);
                    result.add(o);
                    result.addAll(beanContainers(
                            o,
                            thisName + "[" + i + "]",
                            verbose, verbosePrefix + "\t"));
                    i++;
                }
                if (verbose) {
                    System.out.printf("%s\t=== end recursion ==================================%n", verbosePrefix);
                }
            } else { // if the object is not base or iterable, but is a specified container:
                if (verbose) {
                    System.out.printf("%n%s\t=== unpacking %s ==================================%n", verbosePrefix, thisName);
                }
                result.addAll(beanContainers(
                        read,
                        thisName,
                        verbose,
                        verbosePrefix + "\t"));
                if (verbose) {
                    System.out.printf("%s\t=== end unpack ==================================%n", verbosePrefix);
                }
            }
        }

        return result;
    }
}

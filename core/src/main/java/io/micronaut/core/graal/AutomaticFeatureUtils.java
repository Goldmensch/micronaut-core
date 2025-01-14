/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.core.graal;

import com.oracle.svm.core.configure.ResourcesRegistry;
import com.oracle.svm.core.jdk.proxy.DynamicProxyRegistry;
import io.micronaut.core.util.ArrayUtils;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature.BeforeAnalysisAccess;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Utility methods for implementing GraalVM.
 *
 * @author Álvaro Sánchez-Mariscal
 * @author graemerocher
 * @since 2.0.0
 */
public final class AutomaticFeatureUtils {

    /**
     * Marks the given class to be initialized at build time, only if it is present.
     *
     * @param access the {@link BeforeAnalysisAccess} instance
     * @param className the class name
     */
    public static void initializeAtBuildTime(BeforeAnalysisAccess access, String className) {
        findClass(access, className).ifPresent(RuntimeClassInitialization::initializeAtBuildTime);
    }

    /**
     * Marks the given class to be initialized at build time, only if it is present.
     *
     * @param access     The {@link BeforeAnalysisAccess} instance
     * @param classNames The class names
     */
    public static void initializeAtBuildTime(BeforeAnalysisAccess access, String... classNames) {
        for (String className : classNames) {
            initializeAtBuildTime(access, className);
        }
    }

    /**
     * Marks the given class to be initialized at runtime, only if it is present.
     *
     * @param access the {@link BeforeAnalysisAccess} instance
     * @param className the class name
     */
    public static void initializeAtRunTime(BeforeAnalysisAccess access, String className) {
        findClass(access, className).ifPresent(RuntimeClassInitialization::initializeAtRunTime);
    }

    /**
     * Marks the given class to be initialized at runtime, only if it is present.
     *
     * @param access     The {@link BeforeAnalysisAccess} instance
     * @param classNames The class names
     */
    public static void initializeAtRunTime(BeforeAnalysisAccess access, String... classNames) {
        for (String className : classNames) {
            initializeAtRunTime(access, className);
        }
    }

    /**
     * Initializes the packages at build time.
     *
     * @param packages The packages
     */
    public static void initializePackagesAtBuildTime(String... packages) {
        RuntimeClassInitialization.initializeAtBuildTime(packages);
    }

    /**
     * Initializes the packages at run time.
     *
     * @param packages The packages
     */
    public static void initializePackagesAtRunTime(String... packages) {
        RuntimeClassInitialization.initializeAtRunTime(packages);
    }

    /**
     * Register all constructors of the given class for runtime reflection, only if the class is present.
     *
     * @param access    The {@link BeforeAnalysisAccess} instance
     * @param className The class name
     */
    public static void registerConstructorsForRuntimeReflection(BeforeAnalysisAccess access, String className) {
        findClass(access, className).ifPresent(AutomaticFeatureUtils::registerConstructorsForRuntimeReflection);
    }

    /**
     * Allows reflection instantiation of the given class, only if it is present.
     *
     * @param access the {@link BeforeAnalysisAccess} instance
     * @param className the class name
     */
    public static void registerClassForRuntimeReflection(BeforeAnalysisAccess access, String className) {
        findClass(access, className).ifPresent(AutomaticFeatureUtils::registerForRuntimeReflection);
    }

    /**
     * Register the class for reflective instantiation at runtime, only if it is present.
     *
     * @param access    The {@link BeforeAnalysisAccess} instance
     * @param className The class name
     */
    public static void registerClassForRuntimeReflectiveInstantiation(BeforeAnalysisAccess access, String className) {
        findClass(access, className).ifPresent(AutomaticFeatureUtils::registerForReflectiveInstantiation);
    }

    /**
     * Register the class and allow reflective instantiation at runtime, only if it is present.
     *
     * @param access    The {@link BeforeAnalysisAccess} instance
     * @param className The class name
     */
    public static void registerClassForRuntimeReflectionAndReflectiveInstantiation(BeforeAnalysisAccess access, String className) {
        findClass(access, className).ifPresent(AutomaticFeatureUtils::registerForRuntimeReflectionAndReflectiveInstantiation);
    }

    /**
     * Allows reflection usage for all methods of the given class, only if it is present.
     *
     * @param access the {@link BeforeAnalysisAccess} instance
     * @param className the class name
     */
    public static void registerMethodsForRuntimeReflection(BeforeAnalysisAccess access, String className) {
        findClass(access, className).ifPresent(AutomaticFeatureUtils::registerMethodsForRuntimeReflection);
    }

    /**
     * Allows reflection usage for all fields of the given class, only if it is present.
     *
     * @param access the {@link BeforeAnalysisAccess} instance
     * @param className the class name
     */
    public static void registerFieldsForRuntimeReflection(BeforeAnalysisAccess access, String className) {
        findClass(access, className).ifPresent(AutomaticFeatureUtils::registerFieldsForRuntimeReflection);
    }

    /**
     * Registers the given interfaces for dynamic proxy generation.
     *
     * @param access     The {@link BeforeAnalysisAccess} instance
     * @param interfaces the list of interfaces that the generated proxy can implement
     */
    public static void addProxyClass(BeforeAnalysisAccess access, String... interfaces) {
        List<Class<?>> classList = new ArrayList<>();
        for (String anInterface : interfaces) {
            Class<?> clazz = access.findClassByName(anInterface);
            if (clazz != null) {
                classList.add(clazz);
            }
        }
        if (classList.size() == interfaces.length) {
            ImageSingletons.lookup(DynamicProxyRegistry.class).addProxyClass(classList.toArray(new Class<?>[interfaces.length]));
        }
    }

    /**
     * Adds resource patterns.
     *
     * @param patterns The patterns
     */
    public static void addResourcePatterns(String... patterns) {
        if (ArrayUtils.isNotEmpty(patterns)) {
            ResourcesRegistry resourcesRegistry = ImageSingletons.lookup(ResourcesRegistry.class);
            if (resourcesRegistry != null) {
                for (String resource : patterns) {
                    resourcesRegistry.addResources(resource);
                }
            }
        }
    }

    /**
     * Adds resource bundles.
     *
     * @param bundles The bundles
     */
    public static void addResourceBundles(String... bundles) {
        if (ArrayUtils.isNotEmpty(bundles)) {
            ResourcesRegistry resourcesRegistry = ImageSingletons.lookup(ResourcesRegistry.class);
            if (resourcesRegistry != null) {
                for (String resource : bundles) {
                    resourcesRegistry.addResourceBundles(resource);
                }
            }
        }
    }

    /**
     * Register class for runtime reflection and allows reflective instantiation. Also register all fields, methods and
     * constructors for runtime reflection; only if the class is present.
     *
     * @param access    The {@link BeforeAnalysisAccess} instance
     * @param className The class name
     */
    public static void registerAllForRuntimeReflectionAndReflectiveInstantiation(BeforeAnalysisAccess access, String className) {
        findClass(access, className).ifPresent(AutomaticFeatureUtils::registerAllForRuntimeReflectionAndReflectiveInstantiation);
    }

    /**
     * Register class for runtime reflection without allowing reflective instantiation. Also register all fields, methods
     * and constructors for runtime reflection; only if the class is present.
     *
     * @param access    The {@link BeforeAnalysisAccess} instance
     * @param className The class name
     */
    public static void registerAllForRuntimeReflection(BeforeAnalysisAccess access, String className) {
        findClass(access, className).ifPresent(AutomaticFeatureUtils::registerAllForRuntimeReflection);
    }

    /**
     * Register class for runtime reflection and allows reflective instantiation. Also register all fields and methods
     * for runtime reflection; only if the class is present.
     *
     * @param access    The {@link BeforeAnalysisAccess} instance
     * @param className The class name
     */
    public static void registerFieldsAndMethodsWithReflectiveAccess(BeforeAnalysisAccess access, String className) {
        findClass(access, className).ifPresent(AutomaticFeatureUtils::registerFieldsAndMethodsWithReflectiveAccess);
    }

    private static void registerAllForRuntimeReflectionAndReflectiveInstantiation(Class<?> clazz) {
        registerForRuntimeReflection(clazz);
        registerForReflectiveInstantiation(clazz);
        registerFieldsForRuntimeReflection(clazz);
        registerMethodsForRuntimeReflection(clazz);
        registerConstructorsForRuntimeReflection(clazz);
    }

    private static void registerAllForRuntimeReflection(Class<?> clazz) {
        registerForRuntimeReflection(clazz);
        registerFieldsForRuntimeReflection(clazz);
        registerMethodsForRuntimeReflection(clazz);
        registerConstructorsForRuntimeReflection(clazz);
    }

    private static void registerFieldsAndMethodsWithReflectiveAccess(Class<?> clazz) {
        registerForRuntimeReflectionAndReflectiveInstantiation(clazz);
        registerMethodsForRuntimeReflection(clazz);
        registerFieldsForRuntimeReflection(clazz);
    }

    private static void registerForRuntimeReflection(Class<?> clazz) {
        RuntimeReflection.register(clazz);
    }

    private static void registerForReflectiveInstantiation(Class<?> clazz) {
        RuntimeReflection.registerForReflectiveInstantiation(clazz);
    }

    private static void registerForRuntimeReflectionAndReflectiveInstantiation(Class<?> clazz) {
        RuntimeReflection.register(clazz);
        RuntimeReflection.registerForReflectiveInstantiation(clazz);
    }

    private static void registerMethodsForRuntimeReflection(Class<?> clazz) {
        for (Method method : clazz.getMethods()) {
            RuntimeReflection.register(method);
        }
    }

    private static void registerFieldsForRuntimeReflection(Class<?> clazz) {
        for (Field field : clazz.getFields()) {
            RuntimeReflection.register(field);
        }
    }

    private static void registerConstructorsForRuntimeReflection(Class<?> clazz) {
        for (Constructor<?> constructor : clazz.getConstructors()) {
            RuntimeReflection.register(constructor);
        }
    }

    private static Optional<Class<?>> findClass(BeforeAnalysisAccess access, String className) {
        return Optional.ofNullable(access.findClassByName(className));
    }

}

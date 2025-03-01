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
package io.micronaut.inject.visitor.util;

import io.micronaut.context.env.CachedEnvironment;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.DefaultAnnotationMetadata;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;

import javax.annotation.processing.ProcessingEnvironment;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Internal common helper functions to share among {@link VisitorContext} implementations.
 * @author Miguel A. Baldi Horlle
 * @since 1.3.0
 */
@Internal
public class VisitorContextUtils {

    /**
     * Get visitor's context options from {@link System#getProperties()}
     * <p>
     * Transforms {@link System#getProperties()} into {@link Map}
     * allowing only Micronaut's properties, filtering everything else.
     * </p>
     * @return options map
     */
    public static Map<String, String> getSystemOptions() {
        return Optional.ofNullable(System.getProperties())
                .map(properties ->
                        properties.stringPropertyNames()
                                .stream()
                                .filter(name -> name.startsWith(VisitorContext.MICRONAUT_BASE_OPTION_NAME))
                                .map(k -> new AbstractMap.SimpleEntry<>(k, CachedEnvironment.getProperty(k)))
                                .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue)))
                .orElse(Collections.emptyMap());
    }

    /**
     * Get visitor's context options from {@link ProcessingEnvironment#getOptions()}
     * <p>
     * Get {@link ProcessingEnvironment#getOptions()}
     * allowing only Micronaut's properties, filtering everything else.
     * </p>
     * @param processingEnv {@link ProcessingEnvironment}
     * @return options map
     */
    public static Map<String, String> getProcessorOptions(ProcessingEnvironment processingEnv) {
        return Optional.ofNullable(processingEnv)
                .map(ProcessingEnvironment::getOptions)
                .map(Map::entrySet)
                .map(Set::stream)
                // Only collects properties with name starting with MICRONAUT_BASE_OPTION_NAME
                .map(entryStream -> entryStream.filter(e -> e.getKey().startsWith(VisitorContext.MICRONAUT_BASE_OPTION_NAME))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                .orElse(Collections.emptyMap());
    }

    /**
     * Contributes repeatable annotation metadata to the given class element.
     *
     * <p>WARNING: for internal use only be the framework</p>
     *
     * @param target        The target
     * @param classElement  The source
     */
    @Internal
    public static void contributeRepeatable(AnnotationMetadata target, ClassElement classElement) {
        contributeRepeatable(target, classElement, new HashSet<>());
    }

    private static void contributeRepeatable(AnnotationMetadata target, ClassElement classElement, Set<ClassElement> alreadySeen) {
        alreadySeen.add(classElement);
        DefaultAnnotationMetadata.contributeRepeatable(target, classElement.getAnnotationMetadata());
        for (ClassElement element : classElement.getTypeArguments().values()) {
            if (alreadySeen.contains(classElement)) {
                continue;
            }
            contributeRepeatable(target, element);
        }
    }
}

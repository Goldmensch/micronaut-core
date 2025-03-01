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
package io.micronaut.inject.ast;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.annotation.DefaultAnnotationMetadata;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;

import java.lang.annotation.Annotation;
import java.util.function.Consumer;

/**
 * Implementation of {@link ParameterElement} via reflection.
 *
 * @author graemerocher
 * @since 2.3.0
 */
@Internal
class ReflectParameterElement implements ParameterElement {
    private final ClassElement classElement;
    private final String name;
    private AnnotationMetadata annotationMetadata = AnnotationMetadata.EMPTY_METADATA;

    ReflectParameterElement(ClassElement classElement, String name) {
        this.classElement = classElement;
        this.name = name;
    }

    @Override
    public boolean isPrimitive() {
        return classElement.isPrimitive();
    }

    @Override
    public boolean isArray() {
        return classElement.isArray();
    }

    @Override
    public int getArrayDimensions() {
        return classElement.getArrayDimensions();
    }

    @NonNull
    @Override
    public ClassElement getType() {
        return classElement;
    }

    @NonNull
    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isProtected() {
        return false;
    }

    @Override
    public boolean isPublic() {
        return true;
    }

    @NonNull
    @Override
    public Object getNativeType() {
        return classElement.getNativeType();
    }

    @NonNull
    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        return this.annotationMetadata;
    }

    @NonNull
    @Override
    public <T extends Annotation> Element annotate(@NonNull String annotationType, @NonNull Consumer<AnnotationValueBuilder<T>> consumer) {
        if (annotationMetadata == AnnotationMetadata.EMPTY_METADATA) {
            final MutableAnnotationMetadata mutableAnnotationMetadata = new MutableAnnotationMetadata();
            this.annotationMetadata = mutableAnnotationMetadata;
            AnnotationValueBuilder<T> builder = AnnotationValue.builder(annotationType);
            consumer.accept(builder);
            mutableAnnotationMetadata.addDeclaredAnnotation(annotationType, builder.build().getValues());
        } else {
            AnnotationValueBuilder<T> builder = AnnotationValue.builder(annotationType);
            consumer.accept(builder);
            this.annotationMetadata = DefaultAnnotationMetadata.mutateMember(annotationMetadata, annotationType, builder.build().getValues());
        }
        return this;
    }

}

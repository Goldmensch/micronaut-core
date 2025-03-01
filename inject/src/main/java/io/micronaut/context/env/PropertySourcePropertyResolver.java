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
package io.micronaut.context.env;

import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.format.MapFormat;
import io.micronaut.core.io.socket.SocketUtils;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.naming.conventions.StringConvention;
import io.micronaut.core.optim.StaticOptimizations;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.EnvironmentProperties;
import io.micronaut.core.util.StringUtils;
import io.micronaut.core.value.MapPropertyResolver;
import io.micronaut.core.value.PropertyResolver;
import io.micronaut.core.value.ValueException;
import org.slf4j.Logger;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>A {@link PropertyResolver} that resolves from one or many {@link PropertySource} instances.</p>
 *
 * @author Graeme Rocher
 * @since 1.0
 */
public class PropertySourcePropertyResolver implements PropertyResolver, AutoCloseable {

    private static final Logger LOG = ClassUtils.getLogger(PropertySourcePropertyResolver.class);

    private static final EnvironmentProperties CURRENT_ENV = StaticOptimizations.get(EnvironmentProperties.class)
            .orElseGet(EnvironmentProperties::empty);
    private static final Pattern DOT_PATTERN = Pattern.compile("\\.");
    private static final String RANDOM_PREFIX = "\\s?random\\.(\\S+?)";
    private static final String RANDOM_UPPER_LIMIT = "(\\(-?\\d+(\\.\\d+)?\\))";
    private static final String RANDOM_RANGE = "(\\[-?\\d+(\\.\\d+)?,\\s?-?\\d+(\\.\\d+)?])";

    private static final Pattern RANDOM_PATTERN = Pattern.compile("\\$\\{" + RANDOM_PREFIX + "(" + RANDOM_UPPER_LIMIT + "|" + RANDOM_RANGE + ")?\\}");

    private static final Object NO_VALUE = new Object();
    private static final PropertyCatalog[] CONVENTIONS = {PropertyCatalog.GENERATED, PropertyCatalog.RAW};
    private static final String WILD_CARD_SUFFIX = ".*";
    protected final ConversionService conversionService;
    protected final PropertyPlaceholderResolver propertyPlaceholderResolver;
    protected final Map<String, PropertySource> propertySources = new ConcurrentHashMap<>(10);
    // properties are stored in an array of maps organized by character in the alphabet
    // this allows optimization of searches by prefix
    @SuppressWarnings("MagicNumber")
    protected final Map<String, Object>[] catalog = new Map[58];
    protected final Map<String, Object>[] rawCatalog = new Map[58];
    protected final Map<String, Object>[] nonGenerated = new Map[58];
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Boolean> containsCache = new ConcurrentHashMap<>(20);
    private final Map<String, Object> resolvedValueCache = new ConcurrentHashMap<>(20);
    private final EnvironmentProperties environmentProperties = EnvironmentProperties.fork(CURRENT_ENV);

    /**
     * Creates a new, initially empty, {@link PropertySourcePropertyResolver} for the given {@link ConversionService}.
     *
     * @param conversionService The {@link ConversionService}
     */
    public PropertySourcePropertyResolver(ConversionService conversionService) {
        this.conversionService = conversionService;
        this.propertyPlaceholderResolver = new DefaultPropertyPlaceholderResolver(this, conversionService);
    }

    /**
     * Creates a new, initially empty, {@link PropertySourcePropertyResolver}.
     */
    public PropertySourcePropertyResolver() {
        this(ConversionService.SHARED);
    }

    /**
     * Creates a new {@link PropertySourcePropertyResolver} for the given {@link PropertySource} instances.
     *
     * @param propertySources The {@link PropertySource} instances
     */
    public PropertySourcePropertyResolver(PropertySource... propertySources) {
        this(ConversionService.SHARED);
        if (propertySources != null) {
            for (PropertySource propertySource : propertySources) {
                addPropertySource(propertySource);
            }
        }
    }

    /**
     * Add a {@link PropertySource} to this resolver.
     *
     * @param propertySource The {@link PropertySource} to add
     * @return This {@link PropertySourcePropertyResolver}
     */
    public PropertySourcePropertyResolver addPropertySource(@Nullable PropertySource propertySource) {
        if (propertySource != null) {
            processPropertySource(propertySource, propertySource.getConvention());
        }
        return this;
    }

    /**
     * Add a property source for the given map.
     *
     * @param name   The name of the property source
     * @param values The values
     * @return This environment
     */
    public PropertySourcePropertyResolver addPropertySource(String name, @Nullable Map<String, ? super Object> values) {
        if (CollectionUtils.isNotEmpty(values)) {
            return addPropertySource(PropertySource.of(name, values));
        }
        return this;
    }

    @Override
    public boolean containsProperty(@Nullable String name) {
        if (StringUtils.isEmpty(name)) {
            return false;
        } else {
            Boolean result = containsCache.get(name);
            if (result == null) {

                for (PropertyCatalog convention : CONVENTIONS) {
                    Map<String, Object> entries = resolveEntriesForKey(name, false, convention);
                    if (entries != null) {
                        if (entries.containsKey(name)) {
                            result = true;
                            break;
                        }
                    }
                }
                if (result == null) {
                    result = false;
                }
                containsCache.put(name, result);
            }
            return result;
        }
    }

    @Override
    public boolean containsProperties(@Nullable String name) {
        if (!StringUtils.isEmpty(name)) {
            for (PropertyCatalog propertyCatalog : CONVENTIONS) {
                Map<String, Object> entries = resolveEntriesForKey(name, false, propertyCatalog);
                if (entries != null) {
                    if (entries.containsKey(name)) {
                        return true;
                    } else {
                        String finalName = name + ".";
                        for (String key : entries.keySet()) {
                            if (key.startsWith(finalName)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    @NonNull
    @Override
    public Collection<String> getPropertyEntries(@NonNull String name) {
        if (!StringUtils.isEmpty(name)) {
            Map<String, Object> entries = resolveEntriesForKey(
                    name, false, PropertyCatalog.NORMALIZED);
            if (entries != null) {
                String prefix = name + '.';
                return entries.keySet().stream().filter(k -> k.startsWith(prefix))
                              .map(k -> {
                                  String withoutPrefix = k.substring(prefix.length());
                                  int i = withoutPrefix.indexOf('.');
                                  if (i > -1) {
                                      return withoutPrefix.substring(0, i);
                                  }
                                  return withoutPrefix;
                              })
                              .collect(Collectors.toSet());
            }
        }
        return Collections.emptySet();
    }

    @Override
    public Set<List<String>> getPropertyPathMatches(String pathPattern) {
        if (StringUtils.isNotEmpty(pathPattern)) {
            Map<String, Object> entries = resolveEntriesForKey(
                pathPattern, false, null);

            if (entries != null) {
                boolean endsWithWildCard = pathPattern.endsWith(WILD_CARD_SUFFIX);
                String resolvedPattern = pathPattern
                    .replace("[*]", "\\[([\\w\\d-]+?)\\]")
                    .replace(".*.", "\\.([\\w\\d-]+?)\\.");
                if (endsWithWildCard) {
                    resolvedPattern = resolvedPattern.replace(WILD_CARD_SUFFIX, "\\S*");
                } else {
                    resolvedPattern += "\\S*";
                }
                Pattern pattern = Pattern.compile(resolvedPattern);
                Set<String> keys = entries.keySet();
                Set<List<String>> results = new HashSet<>(keys.size());
                for (String key : keys) {
                    Matcher matcher = pattern.matcher(key);
                    if (matcher.matches()) {
                        int i = matcher.groupCount();
                        if (i > 0) {
                            if (i == 1) {
                                results.add(Collections.singletonList(matcher.group(1)));
                            } else {
                                List<String> resolved = new ArrayList<>(i);
                                for (int j = 0; j < i; j++) {
                                    resolved.add(matcher.group(j + 1));
                                }
                                results.add(CollectionUtils.unmodifiableList(resolved));
                            }
                        }
                    }
                }

                return Collections.unmodifiableSet(results);
            }
        }
        return Collections.emptySet();
    }

    @Override
    public @NonNull Map<String, Object> getProperties(String name, StringConvention keyFormat) {
        if (!StringUtils.isEmpty(name)) {
            Map<String, Object> entries = resolveEntriesForKey(name, false, keyFormat == StringConvention.RAW ? PropertyCatalog.RAW : PropertyCatalog.GENERATED);
            if (entries != null) {
                if (keyFormat == null) {
                    keyFormat = StringConvention.RAW;
                }
                return resolveSubMap(
                        name,
                        entries,
                        ConversionContext.MAP,
                        keyFormat,
                        MapFormat.MapTransformation.FLAT
                );
            } else {
                entries = resolveEntriesForKey(name, false, PropertyCatalog.GENERATED);
                if (keyFormat == null) {
                    keyFormat = StringConvention.RAW;
                }
                if (entries == null) {
                    return Collections.emptyMap();
                }
                return resolveSubMap(
                        name,
                        entries,
                        ConversionContext.MAP,
                        keyFormat,
                        MapFormat.MapTransformation.FLAT
                );
            }
        }
        return Collections.emptyMap();
    }

    @Override
    public <T> Optional<T> getProperty(@NonNull String name, @NonNull ArgumentConversionContext<T> conversionContext) {
        if (StringUtils.isEmpty(name)) {
            return Optional.empty();
        } else {
            Objects.requireNonNull(conversionContext, "Conversion context should not be null");
            Class<T> requiredType = conversionContext.getArgument().getType();
            boolean cacheableType = ClassUtils.isJavaLangType(requiredType);
            Object cached = cacheableType ? resolvedValueCache.get(cacheKey(name, requiredType)) : null;
            if (cached != null) {
                return cached == NO_VALUE ? Optional.empty() : Optional.of((T) cached);
            } else {
                Map<String, Object> entries = resolveEntriesForKey(name, false, PropertyCatalog.GENERATED);
                if (entries == null) {
                    entries = resolveEntriesForKey(name, false, PropertyCatalog.RAW);
                }
                if (entries != null) {
                    Object value = entries.get(name);
                    if (value == null) {
                        value = entries.get(normalizeName(name));
                        if (value == null && name.indexOf('[') == -1) {
                            // last chance lookup the raw value
                            Map<String, Object> rawEntries = resolveEntriesForKey(name, false, PropertyCatalog.RAW);
                            value = rawEntries != null ? rawEntries.get(name) : null;
                            if (value != null) {
                                entries = rawEntries;
                            }
                        }
                    }
                    if (value == null) {
                        int i = name.indexOf('[');
                        if (i > -1 && name.endsWith("]")) {
                            String newKey = name.substring(0, i);
                            value = entries.get(newKey);
                            String index = name.substring(i + 1, name.length() - 1);
                            if (value != null) {
                                if (StringUtils.isNotEmpty(index)) {
                                    if (value instanceof List) {
                                        try {
                                            value = ((List) value).get(Integer.valueOf(index));
                                        } catch (NumberFormatException e) {
                                            // ignore
                                        }
                                    } else if (value instanceof Map) {
                                        try {
                                            value = ((Map) value).get(index);
                                        } catch (NumberFormatException e) {
                                            // ignore
                                        }
                                    }
                                }
                            } else {
                                if (StringUtils.isNotEmpty(index)) {
                                    String subKey = newKey + '.' + index;
                                    value = entries.get(subKey);
                                }
                            }
                        }
                    }

                    if (value != null) {
                        Optional<T> converted;
                        value = resolvePlaceHoldersIfNecessary(value);
                        if (requiredType.isInstance(value) && !CollectionUtils.isIterableOrMap(requiredType)) {
                            converted = (Optional<T>) Optional.of(value);
                        } else {
                            converted = conversionService.convert(value, conversionContext);
                        }

                        if (LOG.isTraceEnabled()) {
                            if (converted.isPresent()) {
                                LOG.trace("Resolved value [{}] for property: {}", converted.get(), name);
                            } else {
                                LOG.trace("Resolved value [{}] cannot be converted to type [{}] for property: {}", value, conversionContext.getArgument(), name);
                            }
                        }

                        if (cacheableType) {
                            resolvedValueCache.put(cacheKey(name, requiredType), converted.orElse((T) NO_VALUE));
                        }
                        return converted;
                    } else if (cacheableType) {
                        resolvedValueCache.put(cacheKey(name, requiredType), NO_VALUE);
                        return Optional.empty();
                    } else if (Properties.class.isAssignableFrom(requiredType)) {
                        Properties properties = resolveSubProperties(name, entries, conversionContext);
                        return Optional.of((T) properties);
                    } else if (Map.class.isAssignableFrom(requiredType)) {
                        Map<String, Object> subMap = resolveSubMap(name, entries, conversionContext);
                        if (!subMap.isEmpty()) {
                            return conversionService.convert(subMap, requiredType, conversionContext);
                        } else {
                            return (Optional<T>) Optional.of(subMap);
                        }
                    } else if (PropertyResolver.class.isAssignableFrom(requiredType)) {
                        Map<String, Object> subMap = resolveSubMap(name, entries, conversionContext);
                        return Optional.of((T) new MapPropertyResolver(subMap, conversionService));
                    }
                }
            }

        }
        if (LOG.isTraceEnabled()) {
            LOG.trace("No value found for property: {}", name);
        }

        Class<T> requiredType = conversionContext.getArgument().getType();
        if (Properties.class.isAssignableFrom(requiredType)) {
            return Optional.of((T) new Properties());
        } else if (Map.class.isAssignableFrom(requiredType)) {
            return Optional.of((T) Collections.emptyMap());
        }
        return Optional.empty();
    }

    @NonNull
    private <T> String cacheKey(@NonNull String name, Class<T> requiredType) {
        return name + '|' + requiredType.getSimpleName();
    }

    /**
     * Returns a combined Map of all properties in the catalog.
     *
     * @param keyConvention The map key convention
     * @param transformation The map format
     * @return Map of all properties
     */
    public Map<String, Object> getAllProperties(StringConvention keyConvention, MapFormat.MapTransformation transformation) {
        Map<String, Object> map = new HashMap<>();
        boolean isNested = transformation == MapFormat.MapTransformation.NESTED;
        Arrays
            .stream(getCatalog(keyConvention == StringConvention.RAW ? PropertyCatalog.RAW : PropertyCatalog.GENERATED))
            .filter(Objects::nonNull)
            .map(Map::entrySet)
            .flatMap(Collection::stream)
            .forEach((Map.Entry<String, Object> entry) -> {
                String k = keyConvention.format(entry.getKey());
                Object value = resolvePlaceHoldersIfNecessary(entry.getValue());
                Map finalMap = map;
                int index = k.indexOf('.');
                if (index != -1 && isNested) {
                    String[] keys = DOT_PATTERN.split(k);
                    for (int i = 0; i < keys.length - 1; i++) {
                        if (!finalMap.containsKey(keys[i])) {
                            finalMap.put(keys[i], new HashMap<>());
                        }
                        Object next = finalMap.get(keys[i]);
                        if (next instanceof Map) {
                            finalMap = ((Map) next);
                        }
                    }
                    finalMap.put(keys[keys.length - 1], value);
                } else {
                    finalMap.put(k, value);
                }
            });

        return map;
    }

    /**
     * @param name              The property name
     * @param entries           The entries
     * @param conversionContext The conversion context
     * @return The subproperties
     */
    protected Properties resolveSubProperties(String name, Map<String, Object> entries, ArgumentConversionContext<?> conversionContext) {
        // special handling for maps for resolving sub keys
        Properties properties = new Properties();
        AnnotationMetadata annotationMetadata = conversionContext.getAnnotationMetadata();
        StringConvention keyConvention = annotationMetadata.enumValue(MapFormat.class, "keyFormat", StringConvention.class)
                                                           .orElse(null);
        if (keyConvention == StringConvention.RAW) {
            entries = resolveEntriesForKey(name, false, PropertyCatalog.RAW);
        }
        String prefix = name + '.';
        entries.entrySet().stream()
            .filter(map -> map.getKey().startsWith(prefix))
            .forEach(entry -> {
                Object value = entry.getValue();
                if (value != null) {
                    String key = entry.getKey().substring(prefix.length());
                    key = keyConvention != null ? keyConvention.format(key) : key;
                    properties.put(key, resolvePlaceHoldersIfNecessary(value.toString()));
                }
            });

        return properties;
    }

    /**
     * @param name              The property name
     * @param entries           The entries
     * @param conversionContext The conversion context
     * @return The submap
     */
    protected Map<String, Object> resolveSubMap(String name, Map<String, Object> entries, ArgumentConversionContext<?> conversionContext) {
        // special handling for maps for resolving sub keys
        AnnotationMetadata annotationMetadata = conversionContext.getAnnotationMetadata();
        StringConvention keyConvention = annotationMetadata.enumValue(MapFormat.class, "keyFormat", StringConvention.class).orElse(null);
        if (keyConvention == StringConvention.RAW) {
            entries = resolveEntriesForKey(name, false, PropertyCatalog.RAW);
        }
        MapFormat.MapTransformation transformation = annotationMetadata.enumValue(
                MapFormat.class,
                "transformation",
                MapFormat.MapTransformation.class)
                .orElse(MapFormat.MapTransformation.NESTED);
        return resolveSubMap(name, entries, conversionContext, keyConvention, transformation);
    }

    /**
     * Resolves a submap for the given name and parameters.
     *
     * @param name The name
     * @param entries The entries
     * @param conversionContext The conversion context
     * @param keyConvention The key convention to use
     * @param transformation The map transformation to apply
     * @return The resulting map
     */
    @NonNull
    protected Map<String, Object> resolveSubMap(
            String name,
            Map<String, Object> entries,
            ArgumentConversionContext<?> conversionContext,
            @Nullable StringConvention keyConvention,
            MapFormat.MapTransformation transformation) {
        final Argument<?> valueType = conversionContext.getTypeVariable("V").orElse(Argument.OBJECT_ARGUMENT);
        boolean valueTypeIsList = List.class.isAssignableFrom(valueType.getType());
        Map<String, Object> subMap = new LinkedHashMap<>(entries.size());

        String prefix = name + '.';
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            final String key = entry.getKey();

            if (valueTypeIsList && key.contains("[") && key.endsWith("]")) {
                continue;
            }

            if (key.startsWith(prefix)) {
                String subMapKey = key.substring(prefix.length());

                Object value = resolvePlaceHoldersIfNecessary(entry.getValue());

                if (transformation == MapFormat.MapTransformation.FLAT) {
                    subMapKey = keyConvention != null ? keyConvention.format(subMapKey) : subMapKey;
                    value = conversionService.convert(value, valueType).orElse(null);
                    subMap.put(subMapKey, value);
                } else {
                    processSubmapKey(
                            subMap,
                            subMapKey,
                            value,
                            keyConvention
                    );
                }
            }
        }
        return subMap;
    }

    /**
     * @param properties The property source
     * @param convention The property convention
     */
    @SuppressWarnings("MagicNumber")
    protected void processPropertySource(PropertySource properties, PropertySource.PropertyConvention convention) {
        this.propertySources.put(properties.getName(), properties);
        synchronized (catalog) {
            for (String property : properties) {

                if (LOG.isTraceEnabled()) {
                    LOG.trace("Processing property key {}", property);
                }

                Object value = properties.get(property);

                if (value instanceof CharSequence) {
                    value = processRandomExpressions(convention, property, (CharSequence) value);
                } else if (value instanceof List) {
                    final ListIterator i = ((List) value).listIterator();
                    while (i.hasNext()) {
                        final Object o = i.next();
                        if (o instanceof CharSequence) {
                            final CharSequence newValue = processRandomExpressions(convention, property, (CharSequence) o);
                            if (newValue != o) {
                                i.set(newValue);
                            }
                        }
                    }
                }

                List<String> resolvedProperties = resolvePropertiesForConvention(property, convention);
                boolean first = true;
                for (String resolvedProperty : resolvedProperties) {
                    int i = resolvedProperty.indexOf('[');
                    if (i > -1) {
                        String propertyName = resolvedProperty.substring(0, i);
                        Map<String, Object> entries = resolveEntriesForKey(propertyName, true, PropertyCatalog.GENERATED);
                        if (entries != null) {
                            entries.put(resolvedProperty, value);
                            expandProperty(resolvedProperty.substring(i), val -> entries.put(propertyName, val), () -> entries.get(propertyName), value);
                        }
                        if (first) {
                            Map<String, Object> normalized = resolveEntriesForKey(resolvedProperty, true, PropertyCatalog.NORMALIZED);
                            if (normalized != null) {
                                normalized.put(propertyName, value);
                            }
                            first = false;
                        }
                    } else {
                        Map<String, Object> entries = resolveEntriesForKey(resolvedProperty, true, PropertyCatalog.GENERATED);
                        if (entries != null) {
                            if (value instanceof List || value instanceof Map) {
                                collapseProperty(resolvedProperty, entries, value);
                            }
                            entries.put(resolvedProperty, value);
                        }
                        if (first) {
                            Map<String, Object> normalized = resolveEntriesForKey(resolvedProperty, true, PropertyCatalog.NORMALIZED);
                            if (normalized != null) {
                                normalized.put(resolvedProperty, value);
                            }
                            first = false;
                        }
                    }
                }

                final Map<String, Object> rawEntries = resolveEntriesForKey(property, true, PropertyCatalog.RAW);
                if (rawEntries != null) {
                    rawEntries.put(property, value);
                }
            }
        }
    }

    private void expandProperty(String property, Consumer<Object> containerSet, Supplier<Object> containerGet, Object actualValue) {
        if (StringUtils.isEmpty(property)) {
            containerSet.accept(actualValue);
            return;
        }
        int i = property.indexOf('[');
        int li = property.indexOf(']');
        if (i == 0 && li > -1) {
            String propertyIndex = property.substring(1, li);
            String propertyRest = property.substring(li + 1);
            Object container = containerGet.get();
            if (StringUtils.isDigits(propertyIndex)) {
                Integer number = Integer.valueOf(propertyIndex);
                List list;
                if (container instanceof List) {
                    list = (List) container;
                } else {
                    list = new ArrayList(10);
                    containerSet.accept(list);
                }
                fill(list, number, null);

                expandProperty(propertyRest, val -> list.set(number, val), () -> list.get(number), actualValue);
            } else {
                Map map;
                if (container instanceof Map) {
                    map = (Map) container;
                } else {
                    map = new LinkedHashMap(10);
                    containerSet.accept(map);
                }

                expandProperty(propertyRest, val -> map.put(propertyIndex, val), () -> map.get(propertyIndex), actualValue);
            }
        } else if (property.startsWith(".")) {
            String propertyName;
            String propertyRest;
            if (i > -1) {
                propertyName = property.substring(1, i);
                propertyRest = property.substring(i);
            } else {
                propertyName = property.substring(1);
                propertyRest = "";
            }
            Object v = containerGet.get();
            Map map;
            if (v instanceof Map) {
                map = (Map) v;
            } else {
                map = new LinkedHashMap(10);
                containerSet.accept(map);
            }
            expandProperty(propertyRest, val -> map.put(propertyName, val), () -> map.get(propertyName), actualValue);
        }
    }

    private void collapseProperty(String prefix, Map<String, Object> entries, Object value) {
        if (value instanceof List) {
            for (int i = 0; i < ((List) value).size(); i++) {
                Object item = ((List) value).get(i);
                if (item != null) {
                    collapseProperty(prefix + "[" + i + "]", entries, item);
                }
            }
            entries.put(prefix, value);
        } else if (value instanceof Map) {
            for (Map.Entry<?, ?> entry: ((Map<?, ?>) value).entrySet()) {
                Object key = entry.getKey();
                if (key instanceof CharSequence) {
                    collapseProperty(prefix + "." + ((CharSequence) key).toString(), entries, entry.getValue());
                }
            }
        } else {
            entries.put(prefix, value);
        }
    }

    private CharSequence processRandomExpressions(PropertySource.PropertyConvention convention, String property, CharSequence str) {
        if (convention != PropertySource.PropertyConvention.ENVIRONMENT_VARIABLE && str.toString().contains(propertyPlaceholderResolver.getPrefix())) {
            StringBuffer newValue = new StringBuffer();
            Matcher matcher = RANDOM_PATTERN.matcher(str);
            boolean hasRandoms = false;
            while (matcher.find()) {
                hasRandoms = true;
                String type = matcher.group(1).trim().toLowerCase();
                String range = matcher.group(2);
                if (range != null) {
                    range = range.substring(1, range.length() - 1);
                }
                String randomValue;
                switch (type) {
                    case "port":
                        randomValue = String.valueOf(SocketUtils.findAvailableTcpPort());
                        break;
                    case "int":
                    case "integer":
                        randomValue = String.valueOf(range == null ? random.nextInt() : getNextIntegerInRange(range, property));
                        break;
                    case "long":
                        randomValue = String.valueOf(range == null ? random.nextLong() : getNextLongInRange(range, property));
                        break;
                    case "float":
                        randomValue = String.valueOf(range == null ? random.nextFloat() : getNextFloatInRange(range, property));
                        break;
                    case "shortuuid":
                        randomValue = UUID.randomUUID().toString().substring(25, 35);
                        break;
                    case "uuid":
                        randomValue = UUID.randomUUID().toString();
                        break;
                    case "uuid2":
                        randomValue = UUID.randomUUID().toString().replace("-", "");
                        break;
                    default:
                        throw new ConfigurationException("Invalid random expression " + matcher.group(0) + " for property: " + property);
                }
                matcher.appendReplacement(newValue, randomValue);
            }

            if (hasRandoms) {
                matcher.appendTail(newValue);
                return newValue.toString();
            }

        }
        return str;
    }

    /**
     * @param name        The name
     * @param allowCreate Whether allows creation
     * @return The map with the resolved entries for the name
     */
    @SuppressWarnings("MagicNumber")
    protected Map<String, Object> resolveEntriesForKey(String name, boolean allowCreate) {
        return resolveEntriesForKey(name, allowCreate, null);
    }

    /**
     * @param name        The name
     * @param allowCreate Whether allows creation
     * @param propertyCatalog The string convention
     * @return The map with the resolved entries for the name
     */
    @SuppressWarnings("MagicNumber")
    protected Map<String, Object> resolveEntriesForKey(String name, boolean allowCreate, @Nullable PropertyCatalog propertyCatalog) {
        if (name.length() == 0) {
            return null;
        }
        final Map<String, Object>[] catalog = getCatalog(propertyCatalog);

        Map<String, Object> entries = null;
        char firstChar = name.charAt(0);
        if (Character.isLetter(firstChar)) {
            int index = firstChar - 65;
            if (index < catalog.length && index >= 0) {
                entries = catalog[index];
                if (allowCreate && entries == null) {
                    entries = new LinkedHashMap<>(5);
                    catalog[index] = entries;
                }
            }
        }
        return entries;
    }

    private Map<String, Object>[] getCatalog(@Nullable PropertyCatalog propertyCatalog) {
        propertyCatalog = propertyCatalog != null ? propertyCatalog : PropertyCatalog.GENERATED;
        return switch (propertyCatalog) {
            case RAW -> this.rawCatalog;
            case NORMALIZED -> this.nonGenerated;
            default -> this.catalog;
        };
    }

    /**
     * Subclasses can override to reset caches.
     */
    protected void resetCaches() {
        containsCache.clear();
        resolvedValueCache.clear();
    }

    private void processSubmapKey(Map<String, Object> map, String key, Object value, @Nullable StringConvention keyConvention) {
        int index = key.indexOf('.');
        final boolean hasKeyConvention = keyConvention != null;
        if (index == -1) {
            key = hasKeyConvention ? keyConvention.format(key) : key;
            map.put(key, value);
        } else {

            String mapKey = key.substring(0, index);
            mapKey = hasKeyConvention ? keyConvention.format(mapKey) : mapKey;
            if (!map.containsKey(mapKey)) {
                map.put(mapKey, new LinkedHashMap<>());
            }
            final Object v = map.get(mapKey);
            if (v instanceof Map) {
                Map<String, Object> nestedMap = (Map<String, Object>) v;
                String nestedKey = key.substring(index + 1);
                processSubmapKey(nestedMap, nestedKey, value, keyConvention);
            } else {
                map.put(mapKey, v);
            }
        }
    }

    private String normalizeName(String name) {
        return name.replace('-', '.');
    }

    private Object resolvePlaceHoldersIfNecessary(Object value) {
        if (value instanceof CharSequence) {
            return propertyPlaceholderResolver.resolveRequiredPlaceholders(value.toString());
        } else if (value instanceof List) {
            List<?> list = (List) value;
            List<?> newList = new ArrayList<>(list);
            final ListIterator i = newList.listIterator();
            while (i.hasNext()) {
                final Object o = i.next();
                if (o instanceof CharSequence) {
                    i.set(resolvePlaceHoldersIfNecessary(o));
                } else if (o instanceof Map) {
                    Map<?, ?> submap = (Map) o;
                    Map<Object, Object> newMap = new LinkedHashMap<>(submap.size());
                    for (Map.Entry<?, ?> entry : submap.entrySet()) {
                        final Object k = entry.getKey();
                        final Object v = entry.getValue();
                        newMap.put(k, resolvePlaceHoldersIfNecessary(v));
                    }
                    i.set(newMap);
                }
            }
            value = newList;
        }
        return value;
    }

    private List<String> resolvePropertiesForConvention(String property, PropertySource.PropertyConvention convention) {
        if (convention == PropertySource.PropertyConvention.ENVIRONMENT_VARIABLE) {
            return environmentProperties.findPropertyNamesForEnvironmentVariable(property);
        }
        return Collections.singletonList(
                NameUtils.hyphenate(property, true)
        );
    }

    private void fill(List list, Integer toIndex, Object value) {
        if (toIndex >= list.size()) {
            for (int i = list.size(); i <= toIndex; i++) {
                list.add(i, value);
            }
        }
    }

    private int getNextIntegerInRange(String range, String property) {
        try {
            String[] tokens = range.split(",");
            int lowerBound = Integer.parseInt(tokens[0]);
            if (tokens.length == 1) {
                return lowerBound >= 0 ? 1 : -1  * (random.nextInt(Math.abs(lowerBound)));
            }
            int upperBound = Integer.parseInt(tokens[1]);
            return lowerBound + (int) (Math.random() * (upperBound - lowerBound));
        } catch (NumberFormatException ex) {
            throw new ValueException("Invalid range: `" + range + "` found for type Integer while parsing property: " + property, ex);
        }
    }

    private long getNextLongInRange(String range, String property) {
        try {
            String[] tokens = range.split(",");
            long lowerBound = Long.parseLong(tokens[0]);
            if (tokens.length == 1) {
                return (long) (Math.random() * (lowerBound));
            }
            long upperBound = Long.parseLong(tokens[1]);
            return lowerBound + (long) (Math.random() * (upperBound - lowerBound));
        } catch (NumberFormatException ex) {
            throw new ValueException("Invalid range: `" + range + "` found for type Long while parsing property: " + property, ex);
        }
    }

    private float getNextFloatInRange(String range, String property) {
        try {
            String[] tokens = range.split(",");
            float lowerBound = Float.parseFloat(tokens[0]);
            if (tokens.length == 1) {
                return (float) (Math.random() * (lowerBound));
            }
            float upperBound = Float.parseFloat(tokens[1]);
            return lowerBound + (float) (Math.random() * (upperBound - lowerBound));
        } catch (NumberFormatException ex) {
            throw new ValueException("Invalid range: `" + range + "` found for type Float while parsing property: " + property, ex);
        }
    }

    @Override
    public void close() throws Exception {
        if (propertyPlaceholderResolver instanceof AutoCloseable) {
            ((AutoCloseable) propertyPlaceholderResolver).close();
        }
    }

    /**
     * The property catalog to use.
     */
    protected enum PropertyCatalog {
        /**
         * The catalog that contains the raw keys.
         */
        RAW,
        /**
         * The catalog that contains normalized keys. A key is normalized into
         * lower case hyphen separated form. For example an environment variable {@code FOO_BAR} would be
         * normalized to {@code foo.bar}.
         */
        NORMALIZED,
        /**
         * The catalog that contains normalized keys and also generated keys. A synthetic key can be generated from
         * an environment variable such as {@code FOO_BAR_BAZ} which will produce the following keys: {@code foo.bar.baz},
         * {@code foo.bar-baz}, and {@code foo-bar.baz}.
         */
        GENERATED
    }
}

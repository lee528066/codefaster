package com.coderfaster.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * JSON Schema 构建器
 * 用于方便地构建 Tool 参数的 JSON Schema
 */
public class JsonSchemaBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode schema;
    private final List<String> requiredFields;

    /**
     * 默认构造函数，创建 object 类型的 Schema
     */
    public JsonSchemaBuilder() {
        this.schema = MAPPER.createObjectNode();
        this.schema.put("type", "object");
        this.schema.set("properties", MAPPER.createObjectNode());
        this.requiredFields = new ArrayList<>();
    }

    private JsonSchemaBuilder(String type) {
        this.schema = MAPPER.createObjectNode();
        this.schema.put("type", type);
        this.requiredFields = new ArrayList<>();
    }

    /**
     * 创建 object 类型的 Schema
     */
    public static JsonSchemaBuilder object() {
        JsonSchemaBuilder builder = new JsonSchemaBuilder("object");
        builder.schema.set("properties", MAPPER.createObjectNode());
        return builder;
    }

    /**
     * 创建 string 类型的 Schema
     */
    public static JsonSchemaBuilder string() {
        return new JsonSchemaBuilder("string");
    }

    /**
     * 创建 integer 类型的 Schema
     */
    public static JsonSchemaBuilder integer() {
        return new JsonSchemaBuilder("integer");
    }

    /**
     * 创建 number 类型的 Schema
     */
    public static JsonSchemaBuilder number() {
        return new JsonSchemaBuilder("number");
    }

    /**
     * 创建 boolean 类型的 Schema
     */
    public static JsonSchemaBuilder bool() {
        return new JsonSchemaBuilder("boolean");
    }

    /**
     * 创建 array 类型的 Schema
     */
    public static JsonSchemaBuilder array(JsonSchemaBuilder items) {
        JsonSchemaBuilder builder = new JsonSchemaBuilder("array");
        builder.schema.set("items", items.build());
        return builder;
    }

    /**
     * 设置描述
     */
    public JsonSchemaBuilder description(String description) {
        schema.put("description", description);
        return this;
    }

    /**
     * 设置默认值
     */
    public JsonSchemaBuilder defaultValue(Object value) {
        if (value == null) {
            return this;
        }
        if (value instanceof String) {
            schema.put("default", (String) value);
        } else if (value instanceof Integer) {
            schema.put("default", (Integer) value);
        } else if (value instanceof Long) {
            schema.put("default", (Long) value);
        } else if (value instanceof Double) {
            schema.put("default", (Double) value);
        } else if (value instanceof Boolean) {
            schema.put("default", (Boolean) value);
        } else if (value instanceof Float) {
            schema.put("default", (Float) value);
        } else if (value instanceof Short) {
            schema.put("default", (Short) value);
        } else if (value instanceof Byte) {
            schema.put("default", (Byte) value);
        }
        return this;
    }

    /**
     * 设置枚举值
     */
    public JsonSchemaBuilder enumValues(String... values) {
        ArrayNode enumArray = MAPPER.createArrayNode();
        for (String value : values) {
            enumArray.add(value);
        }
        schema.set("enum", enumArray);
        return this;
    }

    /**
     * 设置字符串最小长度
     */
    public JsonSchemaBuilder minLength(int minLength) {
        schema.put("minLength", minLength);
        return this;
    }

    /**
     * 设置字符串最大长度
     */
    public JsonSchemaBuilder maxLength(int maxLength) {
        schema.put("maxLength", maxLength);
        return this;
    }

    /**
     * 设置数字最小值
     */
    public JsonSchemaBuilder minimum(Number minimum) {
        schema.put("minimum", minimum.doubleValue());
        return this;
    }

    /**
     * 设置数字最大值
     */
    public JsonSchemaBuilder maximum(Number maximum) {
        schema.put("maximum", maximum.doubleValue());
        return this;
    }

    /**
     * 添加属性（object 类型专用）
     */
    public JsonSchemaBuilder property(String name, JsonSchemaBuilder propertySchema) {
        ObjectNode properties = (ObjectNode) schema.get("properties");
        if (properties == null) {
            properties = MAPPER.createObjectNode();
            schema.set("properties", properties);
        }
        properties.set(name, propertySchema.build());
        return this;
    }

    /**
     * 添加必填属性（object 类型专用）
     */
    public JsonSchemaBuilder requiredProperty(String name, JsonSchemaBuilder propertySchema) {
        property(name, propertySchema);
        requiredFields.add(name);
        return this;
    }

    /**
     * 标记当前属性为必填（用于链式调用）
     */
    public JsonSchemaBuilder required() {
        // 这个方法主要用于语义表达，实际的 required 由 parent 的 requiredProperty 处理
        return this;
    }

    /**
     * 设置数组最小元素数
     */
    public JsonSchemaBuilder minItems(int minItems) {
        schema.put("minItems", minItems);
        return this;
    }

    /**
     * 设置数组最大元素数
     */
    public JsonSchemaBuilder maxItems(int maxItems) {
        schema.put("maxItems", maxItems);
        return this;
    }

    /**
     * 不允许额外属性（object 类型专用）
     */
    public JsonSchemaBuilder noAdditionalProperties() {
        schema.put("additionalProperties", false);
        return this;
    }

    // ========== 便捷方法 ==========

    /**
     * 添加字符串属性（便捷方法）
     */
    public JsonSchemaBuilder addProperty(String name, String type, String description) {
        JsonSchemaBuilder propSchema;
        switch (type) {
            case "string":
                propSchema = string();
                break;
            case "integer":
                propSchema = integer();
                break;
            case "number":
                propSchema = number();
                break;
            case "boolean":
                propSchema = bool();
                break;
            case "object":
                propSchema = object();
                break;
            default:
                propSchema = string();
        }
        propSchema.description(description);
        return property(name, propSchema);
    }

    /**
     * 添加枚举属性（便捷方法）
     */
    public JsonSchemaBuilder addEnumProperty(String name, Collection<String> values, String description) {
        if (CollectionUtils.isEmpty(values)) {
            return this;
        }
        JsonSchemaBuilder propSchema = string()
            .description(description)
            .enumValues(values.toArray(new String[0]));
        return property(name, propSchema);
    }

    /**
     * 添加枚举属性（便捷方法，varargs版本）
     */
    public JsonSchemaBuilder addEnumProperty(String name, String description, String... values) {
        JsonSchemaBuilder propSchema = string()
            .description(description)
            .enumValues(values);
        return property(name, propSchema);
    }

    /**
     * 添加对象属性（便捷方法，带自定义 schema）
     */
    public JsonSchemaBuilder addObjectProperty(String name, String description, JsonNode objectSchema) {
        if (StringUtils.isBlank(name) || StringUtils.isBlank(description) || objectSchema == null) {
            return this;
        }
        ObjectNode properties = (ObjectNode) schema.get("properties");
        if (properties == null) {
            properties = MAPPER.createObjectNode();
            schema.set("properties", properties);
        }

        ObjectNode propSchema = MAPPER.createObjectNode();
        propSchema.put("type", "object");
        propSchema.put("description", description);

        // Copy properties from provided schema
        if (objectSchema != null) {
            if (objectSchema.has("properties")) {
                propSchema.set("properties", objectSchema.get("properties"));
            }
            if (objectSchema.has("required")) {
                propSchema.set("required", objectSchema.get("required"));
            }
        }

        properties.set(name, propSchema);
        return this;
    }

    /**
     * 添加数组属性（便捷方法）
     */
    public JsonSchemaBuilder addArrayProperty(String name, String itemType, String description) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Property name cannot be null or blank");
        }
        JsonSchemaBuilder itemSchema;
        switch (itemType) {
            case "string":
                itemSchema = string();
                break;
            case "integer":
                itemSchema = integer();
                break;
            case "number":
                itemSchema = number();
                break;
            case "boolean":
                itemSchema = bool();
                break;
            case "object":
                itemSchema = object();
                break;
            default:
                itemSchema = string();
        }
        JsonSchemaBuilder arraySchema = array(itemSchema).description(description);
        return property(name, arraySchema);
    }

    /**
     * 添加数组属性（便捷方法，带自定义 item schema）
     */
    public JsonSchemaBuilder addArrayProperty(String name, JsonNode itemSchema, String description) {
        if (name == null) {
            throw new IllegalArgumentException("Property name cannot be null");
        }
        ObjectNode properties = (ObjectNode) schema.get("properties");
        if (properties == null) {
            properties = MAPPER.createObjectNode();
            schema.set("properties", properties);
        }

        ObjectNode arraySchema = MAPPER.createObjectNode();
        arraySchema.put("type", "array");
        if (description != null) {
            arraySchema.put("description", description);
        }
        arraySchema.set("items", itemSchema);

        properties.set(name, arraySchema);
        return this;
    }

    /**
     * 设置必填字段（便捷方法）
     */
    public JsonSchemaBuilder setRequired(List<String> fields) {
        if (CollectionUtils.isNotEmpty(fields)) {
            requiredFields.clear();
            requiredFields.addAll(fields);
        }
        return this;
    }

    /**
     * 添加必填字段（便捷方法）
     */
    public JsonSchemaBuilder addRequired(String... fields) {
        for (String field : fields) {
            if (!requiredFields.contains(field)) {
                requiredFields.add(field);
            }
        }
        return this;
    }

    /**
     * 构建 JSON Schema
     */
    public JsonNode build() {
        if (!requiredFields.isEmpty()) {
            ArrayNode requiredArray = MAPPER.createArrayNode();
            for (String field : requiredFields) {
                requiredArray.add(field);
            }
            schema.set("required", requiredArray);
        }
        return schema.deepCopy();
    }
}

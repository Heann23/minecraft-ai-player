package me.herry.minecraftAI.ai.experience;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;

/**
 * 기록을 한 줄짜리 JSON 으로 바꾼다. 값만 담은 것(record, Map, 목록, 문자열, 숫자, 참거짓, enum)만 다룬다.
 * record 는 구성 요소의 이름을 키로 하는 객체가 된다. 구성 요소의 순서를 지키므로 같은 값은 언제나 같은 줄이 된다.
 * sealed interface 를 구현한 record 는 어느 것인지 가릴 수 있게 "kind" 에 클래스 이름을 넣는다.
 * 외부 라이브러리에 기대지 않으므로 서버 없이 시험할 수 있다.
 */
public final class JsonLines {
    private JsonLines() {
    }

    public static String toJson(Object value) {
        StringBuilder out = new StringBuilder(256);
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String text -> writeString(out, text);
            case Boolean flag -> out.append(flag.booleanValue());
            case Double number -> writeDouble(out, number);
            case Float number -> writeDouble(out, number.doubleValue());
            case Number number -> out.append(number);
            case Enum<?> constant -> writeString(out, constant.name());
            case Map<?, ?> map -> writeMap(out, map);
            case Collection<?> items -> writeList(out, items);
            case Record record -> writeRecord(out, record);
            default -> throw new IllegalArgumentException("Cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    // JSON 에는 NaN 과 무한대가 없다. 읽는 쪽이 깨지지 않게 null 로 적는다.
    private static void writeDouble(StringBuilder out, double number) {
        if (Double.isNaN(number) || Double.isInfinite(number)) out.append("null");
        else out.append(number);
    }

    private static void writeMap(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) out.append(',');
            first = false;
            writeString(out, String.valueOf(entry.getKey()));
            out.append(':');
            write(out, entry.getValue());
        }
        out.append('}');
    }

    private static void writeList(StringBuilder out, Collection<?> items) {
        out.append('[');
        boolean first = true;
        for (Object item : items) {
            if (!first) out.append(',');
            first = false;
            write(out, item);
        }
        out.append(']');
    }

    private static void writeRecord(StringBuilder out, Record record) {
        out.append('{');
        boolean first = true;
        if (isVariant(record.getClass())) {
            out.append("\"kind\":");
            writeString(out, record.getClass().getSimpleName());
            first = false;
        }
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            if (!first) out.append(',');
            first = false;
            writeString(out, component.getName());
            out.append(':');
            write(out, read(record, component));
        }
        out.append('}');
    }

    private static boolean isVariant(Class<?> type) {
        for (Class<?> parent : type.getInterfaces()) {
            if (parent.isSealed()) return true;
        }
        return false;
    }

    private static Object read(Record record, RecordComponent component) {
        try {
            Method accessor = component.getAccessor();
            // 공개되지 않은 record (다른 클래스 안에 넣어 둔 것 등)도 읽을 수 있게 한다.
            accessor.trySetAccessible();
            return accessor.invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot read " + component.getName() + " of " + record.getClass().getName(), e);
        }
    }

    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}

package io.github.ralfspoeth.json.io;

/**
 * Thrown by the {@link JsonReader}; contains a message, a row, and a column,
 * both starting by 1
 */
public class JsonParseException extends RuntimeException {

    /**
     * row and col where the exception occurred
     */
    private final int row, column;

    /**
     * Instantiate exception.
     * @param message the message; passed to {@code super}
     * @param row the input row; starting at 1
     * @param column the column in that row; starting at 1
     */
    public JsonParseException(String message, int row, int column) {
        super(message);
        this.row = row;
        this.column = column;
    }

    /**
     * Generates a message containing the row and column.
     * @return a message
     */
    @Override
    public String getMessage() {
        return "%s at row %d, column %d".formatted(super.getMessage(), row, column);
    }
}

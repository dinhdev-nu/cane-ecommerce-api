package vn.cane.admin.domain;

public enum StaffRole {
    OWNER("owner"),
    OPERATOR("operator");

    private final String databaseValue;

    StaffRole(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public static StaffRole parse(String value) {
        for (var role : values()) {
            if (role.databaseValue.equals(value)) return role;
        }
        throw new IllegalArgumentException("Role must be owner or operator");
    }
}

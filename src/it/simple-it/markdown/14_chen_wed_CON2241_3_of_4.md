# Examples 1/2 #

## Lambda expressions ##

```java
    shapes.forEach(s -> {
        if (s.getColor() == RED)
            s.setColor(BLUE);
    })
```

## Bulk operations on Collections ##

```java
    List<Shape> blueBlocks = shapes.stream()
         .filter(s -> s.getColor() == BLUE)
         .into(new ArrayList<>());
```


# Examples 2/2 #

## Parallelism ##

```java
    int sumOfWeight 
        = shapes.parallelStream()
                .filter(s -> s.getColor() == BLUE)
                .map(Shape::getWeight)
                .sum();
```


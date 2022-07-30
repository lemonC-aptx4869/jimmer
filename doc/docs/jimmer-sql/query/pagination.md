---
sidebar_position: 2
title: Pagination Query
---

:::tip
The way jimmer-sql implements pagination queries is very distinctive, which is one of the features that distinguish jimmer-sql from other ORMs.
:::

## Automatically create count-query

Usually, paging query requires two SQL statements, one for querying the total row count of data, and the other one for querying data in one page, let's call them count-query and data-query.

These two SQL statements have both the same parts and different parts, and it is difficult to share code unless the SQL logic is very simple.

jimmer-sql is specially designed for paging queries. Developers only need to write data-query, and jimmer-sql will automatically create count-query.

```java
// α
ConfigurableTypedRootQuery<BookTable, Book> query = sqlClient
    .createQuery(BookTable.class, (q, book) -> {
        ...
        Some code for dynamic table joins, 
        dynamic filters and dynamic orders 
        ...
        return q.select(book);
    });

// β
TypedRootQuery<Long> countQuery = query
    // highlight-next-line
    .reselect((oldQuery, book) ->
        oldQuery.select(book.count())
    )
    // highlight-next-line
    .withoutSortingAndPaging();

// γ
int rowCount = countQuery.execute().get(0).intValue();
List<Book> books = query.limit(10, 90).execute();
```

1. Code at `α`:
    data-query is created by developer.

2. Code at `β`:

    On the basis of data-query, quickly create a the count-query.
    - `reselect`: Replace the `select` clause of the original query with the total row count selection.
    - `withoutSortingAndPaging`: Ignore the `order by` clause and paging (if any) of the original query.

3. Code at `γ`:

Use count-query to query the total row count, and use data-query to query the data in one page. These two queries each perform their own duties.

:::caution

Restrictions of `reselect`

1. A query created by reselect cannot be further reselected, which will result in an exception.
2. If the select clause of the original query contains an aggregate function, an exception will be thrown.
3. If the original query contains a `group by` clause, an exception will be thrown.
:::

## Automatically optimize count-query

### Optimization rule

In order to make the performance of count-query as high as possible, jimmer-sql optimizes count-query and deletes unnecessary table joins for the original data-query query.

Note that the following table joins cannot be removed by the optimizer

1. The table join used by the where clause.
    
    That is, table joins only used by the `select` or `order by` clauses of the original data-query's top-level query may be deleted by optimization.

2. Table joins based on collection associations.
    
    Collection associations, that is, one-to-many or many-to-many associations. Such table joins inevitably affect the row count of joined results, so these table joins must be preserved in count-query.
    
    Obviously, the table joins that can be deleted by the optimizer are all based on reference associations, that is, many-to-one or one-to-one associations.

If the table join base on reference association does not affect the row count result, it can be removed. there are two possibilities

1. The join type is left outer join

2. Although the join type is an inner join, the association is based on a foreign key and the foreign key is not null.

In summary, to determine whether a table join copied from the original data-query can be automatically deleted in the count-query, the following optimization rules need to be adopted

<table>
    <tr>
        <td rowspan="4">
            AND
        </td>
        <td colspan="2">
            Whether the association is based on reference, i.e. many-to-one or one-to-one
        </td>
    </tr>
    <tr>
        <td colspan="2">
            Is it only used by the `select` or `order by` clause of the original data-query's top-level query
        </td>
    </tr>
    <tr>
        <td rowspan="2">
            OR
        </td>
        <td>
            Is it a left outer join
        </td>
    </tr>
    <tr>
        <td>
            Is the association non-null
        </td>
    </tr>
</table>

### Scenario that cannot be optimized

```java
ConfigurableTypedRootQuery<BookTable, Book> query = 
    sqlClient.createQuery(BookTable.class, (q, book) -> {
        q.where(
            book.price().between(
                new BigDecimal(20),
                new BigDecimal(30)
            )
        );
        q
            // highlight-next-line
            .orderBy(book.store().name()) // α
            .orderBy(book.name());
        return q.select(book);
    });

TypedRootQuery<Long> countQuery = query
    .reselect((oldQuery, book) ->
        oldQuery.select(book.count())
    )
    .withoutSortingAndPaging();

int rowCount = countQuery.execute().get(0).intValue();
System.out.println(rowCount);
```

Code at `α`

Although `table.store()` is only used by the `order by` clause, not by the where clause, but
- `table.store()` is an inner join
- `Book.store` association is nullable

Therefore, the optimization rules do not take effect, and count-query still needs to retain `table.store()`, and finally generate a JOIN clause in SQL

```sql
select 
    count(tb_1_.ID) 
from BOOK as tb_1_ 
/* highlight-start */
inner join BOOK_STORE as tb_2_ 
    on tb_1_.STORE_ID = tb_2_.ID
/* highlight-end */ 
where tb_1_.PRICE between ? and ?
```

### Scenario that can be optimized

For the situation discussed above that cannot be optimized, any of the following ways can be used to make the optimization take effect

1. Modify the `Book.store` association to non-null, ie `@ManyToOne(optional = false)`
2. Use left outer join

Here, we choose the second option, use the left outer join

```java
ConfigurableTypedRootQuery<BookTable, Book> query = 
    sqlClient.createQuery(BookTable.class, (q, book) -> {
        q.where(
            book.price().between(
                new BigDecimal(20),
                new BigDecimal(30)
            )
        );
        q
            // highlight-next-line
            .orderBy(book.store(JoinType.LEFT).name())
            .orderBy(book.name());
        return q.select(book);
    });

TypedRootQuery<Long> countQuery = query
    .reselect((oldQuery, book) ->
        oldQuery.select(book.count())
    )
    .withoutSortingAndPaging();

int rowCount = countQuery.execute().get(0).intValue();
System.out.println(rowCount);
```

Now, the optimization can take effect, and the final count-query generates SQL as follows
```sql
select 
    count(tb_1_.ID) 
from BOOK as tb_1_ 
where tb_1_.PRICE between ? and ?
```

## Dialect and data-query

What we discussed above are all count-query, paging query finally needs to execute the original data-query query data

```java
List<Book> books = query
    // highlight-next-line
    .limit(10, 90)
    .execute();
```

Here, `limit(limit, offset)` sets the pagination range.

Unfortunately, different databases support paginated queries quite differently. Therefore, you need to specify the dialect when creating the SqlClient

```java
SqlClient sqlClient = SqlClient
    .newBuilder()
    // highlight-next-line
    .setDialect(new H2Dialect())
    ... ...
    .build();
```

The currently supported dialects and their respective generated pagination SQL are as follows

### Default behavior(including DefaultDialect, H2Dialect and PostgresDialect)

```sql
select 
    tb_1_.ID, 
    tb_1_.NAME, 
    tb_1_.EDITION, 
    tb_1_.PRICE, 
    tb_1_.STORE_ID
from BOOK as tb_1_ 
left join BOOK_STORE as tb_2_ 
    on tb_1_.STORE_ID = tb_2_.ID 
where tb_1_.PRICE between ? and ? 
order by tb_2_.NAME asc, tb_1_.NAME asc 
/* highlight-next-line */
limit ? offset ?
```

### MySqlDialect

```sql
select 
    tb_1_.ID, 
    tb_1_.NAME, 
    tb_1_.EDITION, 
    tb_1_.PRICE, 
    tb_1_.STORE_ID
from BOOK as tb_1_ 
left join BOOK_STORE as tb_2_ 
    on tb_1_.STORE_ID = tb_2_.ID 
where tb_1_.PRICE between ? and ? 
order by tb_2_.NAME asc, tb_1_.NAME asc 
/* highlight-next-line */
limit ?, ?
```

### SqlServerDialect

```sql
select 
    tb_1_.ID, 
    tb_1_.NAME, 
    tb_1_.EDITION, 
    tb_1_.PRICE, 
    tb_1_.STORE_ID
from BOOK as tb_1_ 
left join BOOK_STORE as tb_2_ 
    on tb_1_.STORE_ID = tb_2_.ID 
where tb_1_.PRICE between ? and ? 
order by tb_2_.NAME asc, tb_1_.NAME asc 
/* highlight-next-line */
offset ? rows fetch next ? rows only
```

### OracleDialect

1. When offset is not zero

    ```sql
    select * from (
        select core__.*, rownum rn__ 
        from (
            select 
                tb_1_.ID, 
                tb_1_.NAME, 
                tb_1_.EDITION, 
                tb_1_.PRICE, 
                tb_1_.STORE_ID
            from BOOK as tb_1_ 
            left join BOOK_STORE as tb_2_ 
                on tb_1_.STORE_ID = tb_2_.ID 
            where tb_1_.PRICE between ? and ? 
            order by tb_2_.NAME asc, tb_1_.NAME asc 
        /* highlight-next-line */
        ) core__ where rownum <= ? /* α */
    /* highlight-next-line */
    ) limited__ where rn__ > ? /* β */
    ```

    The variable at `α` is `limit + offset`, and the variable at `β` is `offset`

2. When offset is zero

    ```sql
    select core__.* from (
        select 
            tb_1_.ID, 
            tb_1_.NAME, 
            tb_1_.EDITION, 
            tb_1_.PRICE, 
            tb_1_.STORE_ID
        from BOOK as tb_1_ 
        left join BOOK_STORE as tb_2_ 
            on tb_1_.STORE_ID = tb_2_.ID 
        where tb_1_.PRICE between ? and ? 
        order by tb_2_.NAME asc, tb_1_.NAME asc 
    /* highlight-next-line */
    ) core__ where rownum <= ? /* α */
    ```

    The variable at `α` is `limit`
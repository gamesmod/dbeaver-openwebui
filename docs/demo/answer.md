Вот запрос: топ-5 клиентов по сумме заказов за последние 30 дней.

```sql
SELECT c.customer_id,
       c.name,
       SUM(o.total_amount) AS revenue,
       COUNT(*)            AS orders
FROM customers c
JOIN orders o ON o.customer_id = c.customer_id
WHERE o.created_at >= CURRENT_DATE - INTERVAL '30 days'
GROUP BY c.customer_id, c.name
ORDER BY revenue DESC
LIMIT 5;
```

Индекс `orders(customer_id, created_at)` ускорит выборку по периоду.

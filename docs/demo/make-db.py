# Демо-база SQLite для скриншотов: клиенты и заказы за последние 60 дней.
import random, sqlite3, sys, datetime
db = sqlite3.connect(sys.argv[1])
db.executescript("""
DROP TABLE IF EXISTS orders; DROP TABLE IF EXISTS customers;
CREATE TABLE customers (customer_id INTEGER PRIMARY KEY, name TEXT NOT NULL, city TEXT);
CREATE TABLE orders (order_id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL REFERENCES customers,
                     created_at TEXT NOT NULL, total_amount NUMERIC(12,2) NOT NULL);
""")
names = ["ООО Ромашка", "АО Вектор", "ИП Смирнов", "ООО Север", "АО Техносфера", "ООО Логистик", "ИП Кузнецова", "ООО Альфа"]
cities = ["Москва", "Казань", "Самара", "Пермь"]
random.seed(7)
for i, n in enumerate(names, 1):
    db.execute("INSERT INTO customers VALUES (?,?,?)", (i, n, random.choice(cities)))
today = datetime.date.today()
for k in range(1, 301):
    d = today - datetime.timedelta(days=random.randint(0, 60))
    db.execute("INSERT INTO orders VALUES (?,?,?,?)", (k, random.randint(1, len(names)), d.isoformat(), round(random.uniform(500, 50000), 2)))
db.commit()

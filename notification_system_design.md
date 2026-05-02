## Stage 1

### Core Actions
The notification platform supports the following REST API endpoints:

#### 1. Get all notifications for a student
GET /api/notifications/{studentId}
Authorization: Bearer <token>
Response 200:
{
"notifications": [
{
"id": "uuid",
"type": "Placement | Event | Result",
"message": "string",
"isRead": false,
"createdAt": "2026-04-22 17:51:30"
}
]
}

#### 2. Get unread notifications
GET /api/notifications/{studentId}/unread
Authorization: Bearer <token>
Response 200:
{
"notifications": [...]
}

#### 3. Mark notification as read
PATCH /api/notifications/{notificationId}/read
Authorization: Bearer <token>
Response 200:
{
"message": "Notification marked as read"
}

#### 4. Mark all notifications as read
PATCH /api/notifications/{studentId}/read-all
Authorization: Bearer <token>
Response 200:
{
"message": "All notifications marked as read"
}

#### 5. Send notification to a student
POST /api/notifications
Authorization: Bearer <token>
Request:
{
"studentId": "string",
"type": "Placement | Event | Result",
"message": "string"
}
Response 201:
{
"id": "uuid",
"message": "Notification sent successfully"
}

#### 6. Send notification to all students
POST /api/notifications/notify-all
Authorization: Bearer <token>
Request:
{
"type": "Placement | Event | Result",
"message": "string"
}
Response 200:
{
"message": "Notifications queued for all students"
}

#### 7. Delete a notification
DELETE /api/notifications/{notificationId}
Authorization: Bearer <token>
Response 200:
{
"message": "Notification deleted"
}

### Real-Time Notification Mechanism
We use **WebSockets** for real-time notifications. When a student logs in, the frontend opens a WebSocket connection to the server. When a new notification is created, the server pushes it instantly to the connected student without them needing to refresh.

Flow:
1. Student logs in → frontend connects to ws://server/ws/{studentId}
2. HR sends a notification → server saves it to DB
3. Server pushes notification instantly to the student's WebSocket connection
4. Frontend displays the notification in real time

---

## Stage 2

### Recommended Database: PostgreSQL

**Reason:** Notifications have a clear relational structure - students, notification types, read status. PostgreSQL supports strong ACID transactions, indexing, and handles structured data very well. It also supports enums natively which suits our notificationType field.

### DB Schema

```sql
CREATE TYPE notification_type AS ENUM ('Placement', 'Event', 'Result');

CREATE TABLE students (
  id SERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  email VARCHAR(255) UNIQUE NOT NULL,
  createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE notifications (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  studentId INT NOT NULL REFERENCES students(id),
  notificationType notification_type NOT NULL,
  message TEXT NOT NULL,
  isRead BOOLEAN DEFAULT FALSE,
  createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### Problems as Data Grows
1. Queries slow down as notifications table grows to millions of rows
2. Fetching unread notifications for a student becomes expensive
3. Storage becomes large over time

### Solutions
1. Add indexes on studentId, isRead, createdAt
2. Archive old notifications to a separate table
3. Use caching (Redis) for frequently accessed data
4. Partition the notifications table by date

### SQL Queries

Get all notifications for a student:
```sql
SELECT * FROM notifications
WHERE studentId = $1
ORDER BY createdAt DESC;
```

Mark notification as read:
```sql
UPDATE notifications
SET isRead = TRUE
WHERE id = $1;
```

Mark all as read:
```sql
UPDATE notifications
SET isRead = TRUE
WHERE studentId = $1;
```

Send a notification:
```sql
INSERT INTO notifications (studentId, notificationType, message)
VALUES ($1, $2, $3);
```

---

## Stage 3

### Is the query accurate?
Yes, the query is logically correct. It fetches all unread notifications for a student ordered by newest first.

### Why is it slow?
The query is slow because:
1. There is no index on studentId or isRead columns
2. SELECT * fetches all columns including large text fields unnecessarily
3. As the table grows to 5 million rows, a full table scan happens every time

### What to change?
```sql
-- Add these indexes
CREATE INDEX idx_notifications_studentId ON notifications(studentId);
CREATE INDEX idx_notifications_studentId_isRead ON notifications(studentId, isRead);
CREATE INDEX idx_notifications_createdAt ON notifications(createdAt DESC);

-- Improved query - select only needed columns
SELECT id, notificationType, message, createdAt
FROM notifications
WHERE studentId = 1042 AND isRead = false
ORDER BY createdAt DESC;
```

### Cost improvement
With indexes, the query goes from a full table scan O(n) to an index lookup O(log n). For 5 million rows this is a massive improvement.

### Should we index every column?
No. Indexing every column is bad advice because:
1. Each index takes extra storage space
2. Every INSERT and UPDATE becomes slower because all indexes must be updated
3. The query planner can get confused with too many indexes
Only index columns that are frequently used in WHERE, ORDER BY, or JOIN clauses.

### Query - students who got placement notification in last 7 days
```sql
SELECT DISTINCT s.id, s.name, s.email
FROM students s
JOIN notifications n ON s.id = n.studentId
WHERE n.notificationType = 'Placement'
AND n.createdAt >= NOW() - INTERVAL '7 days';
```

---

## Stage 4

### Problem
Fetching notifications on every page load is overwhelming the database.

### Solutions

#### 1. Redis Caching
Cache the notifications for each student in Redis with a TTL of 60 seconds. On page load, check Redis first. Only query the DB if cache is empty.

Tradeoff: Student may see slightly stale data for up to 60 seconds. But DB load reduces dramatically.

#### 2. Pagination
Instead of fetching all notifications, fetch only 20 at a time using LIMIT and OFFSET.

```sql
SELECT * FROM notifications
WHERE studentId = $1
ORDER BY createdAt DESC
LIMIT 20 OFFSET $2;
```

Tradeoff: Requires frontend changes. Deep pagination can still be slow.

#### 3. Read-through Cache
Only fetch unread notifications on page load. Mark them as read lazily in the background.

Tradeoff: Slightly complex to implement but very effective.

---

## Stage 5

### Shortcomings of the current implementation
1. It is synchronous - sending 50,000 emails one by one is extremely slow
2. If send_email fails midway (as it did for 200 students), there is no retry mechanism
3. No way to track which students received the notification and which did not
4. DB insert and email send happening together in a loop means partial failures leave inconsistent state

### What happened when send_email failed for 200 students?
Those 200 students never received the email and there is no record of the failure. The system has no way to retry them.

### Redesigned Solution
function notify_all(student_ids: array, message: string):
// Step 1: Save all notifications to DB first
for student_id in student_ids:
save_to_db(student_id, message)  // DB insert is fast and reliable
// Step 2: Push jobs to a message queue (e.g. RabbitMQ or Redis Queue)
for student_id in student_ids:
queue.push({ student_id, message, type: "email" })
queue.push({ student_id, message, type: "in_app" })
// Worker processes the queue independently with retry logic
function worker():
job = queue.pop()
try:
if job.type == "email":
send_email(job.student_id, job.message)
else:
push_to_app(job.student_id, job.message)
mark_job_done(job)
catch error:
retry_job(job, max_retries=3)
log_failure(job)

### Should DB save and email send happen together?
No. They should be separate because:
1. DB insert is fast and reliable. Email sending depends on an external API that can fail.
2. If we couple them, a failed email means the notification is never saved either.
3. Save to DB first always, then send email asynchronously via a queue with retry logic.

---

## Stage 6

### Approach - Priority Inbox using a Max Heap

To find the top N most important unread notifications efficiently, we use a Max Heap (Priority Queue).

**Priority Score formula:**
- Placement = weight 3
- Result = weight 2  
- Event = weight 1
- Recency score = 1 / (minutes since notification) — newer notifications score higher

**Final score = type_weight * 1000 + recency_score**

This ensures Placement always ranks above Result which always ranks above Event, and within the same type, newer notifications appear first.

**Handling new notifications:**
When a new notification arrives, we add it to the heap. If heap size exceeds N, we remove the lowest priority item. This keeps the top N updated efficiently at O(log N) per insertion.

See the code file `PriorityInbox.java` for the implementation.
# Java Final Project Report

## 1. Project Overview

Our final project is a Yelp-like web application that enables users to discover nearby merchants, share reviews, claim coupons, and interact socially. It is designed to simulate a real-world local lifestyle platform, combining features of social networking, e-commerce, and location-based services.  
**This application is designed for the Chinese market, so the user interface and content are primarily in Chinese.**

## 2. Project Features

The main functionalities include:

### User Login & Registration
- Phone-based login with verification codes  
- Auto-registration for first-time users

### Merchant Browsing
- Categorized merchant listings  
- Detailed merchant pages with dynamic content

### Daily Check-in
- Tracks consecutive check-in days  
- Bit-map structure for efficient date marking

### Review Posting
- Users can publish review posts with images and text  
- Users can like posts; likes are cached in Redis  
- Real-time ranking based on likes

### Coupon System
- Users can claim and use coupons  
- Flash sale (seckill) events with concurrency control using Redis and Lua scripts

### Social Features
- Follow/unfollow other users  
- View followers/following lists  
- Feed updates from followed users

### Location-Based Services
- Search for nearby merchants  
- Results sorted by distance to the user

### Order System
- Flash-sale order submission  
- View personal order history

## 3. Technology Stack

This project is built using the following technologies:

- **Spring Boot**: Used for rapid backend development with automatic configuration of common components  
- **MySQL**: Stores structured data such as users, merchants, and reviews  
- **MyBatis-Plus**: Simplifies database interaction using ORM and code generation  
- **Lombok**: Reduces boilerplate code by automatically generating methods with annotations  
- **Hutool**: Provides utility functions for common tasks like date/time processing, file operations, etc.  
- **Redis**: Enhances system performance through caching, distributed locks, and bitmap operations  

## 4. Module Breakdown

- **User Login Module**: Manages authentication using Redis and interceptors  
- **Merchant Query Module**: Reads category data from Redis or falls back to MySQL  
- **Coupon Seckill Module**: Implements concurrency-safe flash sales with Lua and distributed locks  
- **Blog Module**: Stores likes in Redis and supports real-time post engagement tracking  
- **Follow & Subscription Module**: Uses push/pull/hybrid strategies to deliver user content to followers  

## 5. Key Technical Implementations

To ensure the stability and performance of our Yelp-like application under high-concurrency and distributed deployment environments, we integrated several advanced technical solutions throughout the development process:

### 5.1 Redis-Based Token Login System

In a traditional single-server setup, user login states are typically maintained using `HttpSession`. However, in a clustered environment with multiple servers, this approach faces issues like session synchronization, memory overhead, and stale session propagation. To solve this, we implemented a **Redis-based token login mechanism**:

- Upon login or registration, the server generates a token, saves the user information in Redis (keyed by the token), and returns the token to the frontend.
- The frontend stores the token in a cookie and includes it in subsequent requests.
- The backend retrieves user data from Redis using the token and stores it in `ThreadLocal` to ensure thread safety and global access across the request's lifecycle.
- We implemented **dual interceptors**:
  - The first interceptor processes all requests, refreshing the token's expiration time and loading user info into ThreadLocal if a valid token is provided.
  - The second interceptor restricts access to protected endpoints, allowing only authenticated users.

This solution solves the session sharing problem in distributed deployments, ensures stateless authentication, and reduces server-side memory pressure.

### 5.2 Caching and Database Protection

To improve response speed and reduce database load, we introduced **caching for merchant data** using Redis. The workflow follows the **cache-aside pattern**: query the cache first; if missed, fetch from the database and write back to the cache.

To address specific caching challenges:

- **Cache Penetration**: For non-existent merchants, we cache null values with a short TTL to avoid repeated DB hits.
- **Cache Breakdown**: For hotspot data that might expire simultaneously and trigger a spike of database access, we adopted two strategies:
  - **Mutex lock**: When the cache is missed, only one thread acquires the lock to rebuild the cache, while others wait.
  - **Logical expiration**: Popular data is never physically expired. Instead, we embed an expiry timestamp in the cached data. Once expired, the system returns the stale data immediately but asynchronously rebuilds the cache using a background thread.

These techniques improve resilience under concurrent access and prevent overload during spikes.

### 5.3 High-Concurrency Seckill System

The flash sale feature requires strict control over user eligibility and stock quantity. We handled several key challenges:

- **Overselling Prevention**: To avoid the classic issue where multiple threads decrement inventory simultaneously, we used **CAS (compare-and-swap)** for optimistic locking, ensuring the stock update is safe under concurrency.
- **One User, One Order Enforcement**: We implemented logic to prevent duplicate orders per user. By synchronizing on a per-user basis (`userId.intern()`), we allowed concurrent access across users while ensuring serial processing within a single user's actions.
- **Cluster-Safe Distributed Lock**: Since JVM-level locks don't work across multiple servers, we built a **Redis-based distributed locking mechanism** using `SETNX` and **Lua scripts** to ensure atomicity of locking and releasing. This allowed correct mutual exclusion even in a multi-node environment.
- **Transactional Consistency**: We ensured order creation and cache invalidation were logically atomic. In single-node environments, this was done within one transactional method. In distributed scenarios, messaging queues would typically be used (though out of scope for this project).

These solutions ensured data correctness and user fairness during high-load events like flash sales.

## 6. Conclusion

This project helped us understand real-world application architecture, the importance of performance optimization (e.g., using Redis), and modular back-end design using Java and Spring Boot. Through implementing diverse features such as login, social feeds, and flash sales, we gained hands-on experience with concurrency control, caching, and database integration.

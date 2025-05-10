package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.LimitVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ILimitVoucherService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private ILimitVoucherService limitVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                //获取队列中的订单信息
                try {
                    //获取消息队列中订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息队列是否成功
                    if (list == null||list.isEmpty()) {
                        //获取失败，说明没有消息，继续下一次循环
                        continue;
                    }

                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //获取成功，可以下单
                    handleVoucherOrder(voucherOrder);

                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常:{}", e.getMessage());
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                //获取队列中的订单信息
                try {
                    //获取pending-list中订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断消息队列是否成功
                    if (list == null||list.isEmpty()) {
                        //获取失败，说明pending-list没有异常消息，结束循环
                        break;
                    }

                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //获取成功，可以下单
                     handleVoucherOrder(voucherOrder);

                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常:{}", e.getMessage());
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                //获取队列中的订单信息
                try {
                    //获取队列中订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常:{}", e.getMessage());
                }
                //创建订单
            }
        }
    }*/


    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        //获取锁
        boolean isLock = lock.tryLock( );
        if (! isLock) {
            //获取锁失败
            log.error("不允许重复下单");
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;
    @Override
    public Result secKillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //订单id
        Long orderId = redisIdWorker.nextId("order");

        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        //判断结果是否为0
        int r = result.intValue();
        if(r != 0){
            //不为0，没有购买资格
            return  Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        //获取代理对象（事务）
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //返回订单id
        return Result.ok(orderId);
    }

    /*@Override
    public Result secKillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();

        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );

        //判断结果是否为0
        int r = result.intValue();
        if(r != 0){
            //不为0，没有购买资格
            return  Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        //为0，有购买资格，把下单信息保存到阻塞队列
        //保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);
        //代金劵id
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列
        orderTasks.add(voucherOrder);

        //获取代理对象（事务）
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();

        //返回订单id
        return Result.ok(orderId);
    }*/

//    @Override
//    public Result secKillVoucher(Long voucherId) {
//        //查询优惠卷
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        //判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //尚未开始
//            return Result.fail("秒杀尚未开始");
//        }
//
//        //判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //已经结束
//            return Result.fail("秒杀已经结束");
//        }
//
//        //判断库存是否充足
//        if (voucher.getStock() < 1) {
//            //库存不足
//            return Result.fail("库存不足");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//
//        //创建锁对象
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//
//        //获取锁
//        boolean isLock = lock.tryLock( );
//        if (! isLock) {
//            //获取锁失败
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            //获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId = voucherOrder.getUserId();
        //查询订单
        int count = query().eq("voucher_id", voucherOrder.getVoucherId()).eq("user_id", userId).count();
        //判断是否存在
        if (count > 0) {
            //用户已经购买过
            log.error("用户已经购买过!");
        }

        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            //扣减失败
            log.error("库存不足！");
        }

        //创建订单
        save(voucherOrder);
    }

    @Override
    public Result commonVoucher(Long voucherId, int buyNumber) {
        return null;
    }

    @Override
    public Result limitVoucher(Long voucherId, int buyNumber) {
        return null;
    }

    @Override
    public Result limitVoucher1(Long voucherId, int buyNumber) {
        Long userId = UserHolder.getUser().getId();
        // 1.查询优惠券
        LimitVoucher limitVoucher = limitVoucherService.getById(voucherId);
        Integer limitCount = limitVoucher.getLimitCount();

        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:voucher:" + voucherId + userId);

        try {

            // 2.判断库存是否充足
            if (limitVoucher.getStock() < buyNumber) {
                // 库存不足
                return Result.fail("库存不足！");
            }

            // 3.判断是否限购
            // 执行查询
            List<VoucherOrder> orderList = this.list(new LambdaQueryWrapper<VoucherOrder>()
                    .eq(VoucherOrder::getUserId, userId)
                    .eq(VoucherOrder::getVoucherId, voucherId));

            // 计算购买数量总和
            int totalBuyNumber = orderList.stream()
                    .mapToInt(VoucherOrder::getBuyNumber)
                    .sum();

            if (totalBuyNumber + buyNumber > limitCount) {
                return Result.fail("超过最大购买限制!");
            }

            // 4. 尝试获取锁，最多等待10s
            boolean isLock = false;
            isLock = redisLock.tryLock(10, TimeUnit.SECONDS);
            // 判断
            if (!isLock) {
                // 获取锁失败，直接返回失败或者重试
                log.error("获取锁失败！");
                return Result.fail("同一时间下单人数过多，请稍后重试");
            }

            //5. 乐观锁扣减库存
            boolean success = limitVoucherService.update()
                    .setSql("stock= stock -" + buyNumber)
                    .eq("voucher_id", voucherId)
                    .ge("stock", buyNumber)
                    .update(); //where id = ? and stock >= buyNumber
            if (!success) {
                //扣减库存
                return Result.fail("库存不足！");
            }
            //6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder().setId(redisIdWorker.nextId("order"))
                    .setVoucherId(voucherId)
                    .setUserId(userId)
                    .setCreateTime(LocalDateTime.now())
                    .setUpdateTime(LocalDateTime.now())
                    .setStatus(1)
                    .setBuyNumber(buyNumber);
            save(voucherOrder);

            //7. 返回结果
            return Result.ok(voucherOrder);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            redisLock.unlock();
        }
    }

    @Override
    public Result limitVoucher2(Long voucherId, int buyNumber) {
        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + voucherId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            return Result.fail("不要重复下单");
        }

        try {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > SystemConstants.MAX_BUY_LIMIT) {
                return Result.fail("超过最大购买限制!");
            }

            // 6.扣减库存
            boolean success = limitVoucherService.update()
                    .setSql("stock = stock - " + buyNumber) // set stock = stock - buynumber
                    .eq("voucher_id", voucherId)
                    .gt("stock", buyNumber) // where id = ? and stock > buynumber
                    .update();
            if (!success) {
                // 扣减失败
                return Result.fail("库存不足");
            }
            // 7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder().setId(redisIdWorker.nextId("order"))
                    .setVoucherId(voucherId)
                    .setUserId(userId)
                    .setCreateTime(LocalDateTime.now())
                    .setUpdateTime(LocalDateTime.now())
                    .setStatus(1)
                    .setBuyNumber(buyNumber);
            save(voucherOrder);
            return Result.ok(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }
}

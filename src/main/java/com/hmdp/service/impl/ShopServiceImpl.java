package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        // Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        // 返回
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 不存在，判断是否命中空值
        if (shopJson != null) {
            // 未命中空值，返回错误
            return null;
        }

        // 实现缓存重建
        // 获取互斥锁
        String LockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(LockKey);
            // 判断是否获取成功
            if (!isLock) {
                 // 获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 获取锁成功，则根据id查询数据库
            shop = getById(id);
            // 模拟重建的延时
            Thread.sleep(200);
            // 不存在，返回错误
            if (shop == null) {
                // 将空值写入redis，解决缓存穿透问题
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        
        }catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
             unLock(LockKey);
        }
        // 返回
        return shop;
    }

    public Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 不存在，判断是否命中空值
        if (shopJson != null) {
            // 命中空值，返回错误
            return null;
        }
        // 不存在，根据id查询数据库
        Shop shop = getById(id);
        // 不存在，返回错误
        if (shop == null) {
            // 将空值写入redis，解决缓存穿透问题
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 返回
        return shop;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        String key = RedisConstants.CACHE_SHOP_KEY + shop.getId();
        stringRedisTemplate.delete(key);
        return Result.ok();
    
    }

}

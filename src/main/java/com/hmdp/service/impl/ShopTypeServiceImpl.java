package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.interfaces.Func;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> cacheQuery() {

        String key = "shop:type";

        //从redis查询商户分类
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);

        //判断是否存在
        if(!StrUtil.isBlank(shopTypeJson)){
            //存在，直接返回
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return shopTypeList;
        }

        //不存在，查询数据库
        List<ShopType> shopTypeList = this.query().orderByAsc("sort").list();

        //不存在，返回错误
        if(shopTypeList == null){
            return new ArrayList<>();
        }

        //存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList));

        //返回
        return shopTypeList;
    }
}

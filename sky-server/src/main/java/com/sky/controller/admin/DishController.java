package com.sky.controller.admin;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.OAuth2Definition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/admin/dish")
@Api(tags = "菜品相關接口")
@Slf4j
public class DishController {

    @Autowired
    private DishService dishService;

    @Autowired
    private RedisTemplate redisTemplate;

    @PostMapping
    @ApiOperation("新增菜品")
    public Result save(@RequestBody DishDTO dishDTO) {
        log.info("新增菜品：{}", dishDTO);
        dishService.saveWithFlavor(dishDTO);

        String key = "dish_" + dishDTO.getCategoryId();
        redisTemplate.delete(key);
        return Result.success();
    }

    @GetMapping("/page")
    @ApiOperation("菜品分頁查詢")
    public Result<PageResult> page(DishPageQueryDTO dishPageQueryDTO) {
        log.info("菜品分頁查詢：{}", dishPageQueryDTO);
        PageResult pageResult = dishService.pageQuery(dishPageQueryDTO);
        return Result.success(pageResult);
    }

    @DeleteMapping
    @ApiOperation("菜品批量刪除")
    public Result delete(@RequestParam List<Long> ids) {
        log.info("菜品批量刪除：{}", ids);
        dishService.deleteBatch(ids);
        // 將所有dish_開頭的key全部清除
        cleanCache("dish_*");

        return Result.success();
    }

    @GetMapping("{id}")
    @ApiOperation("根據id查詢菜品")
    public Result<DishVO> getById(@PathVariable Long id) {
        log.info("根據id查詢菜品：{}", id);
        DishVO dishVO = dishService.getByIdWithFlavor(id);
        return Result.success(dishVO);
    }

    @PutMapping
    @ApiOperation("修改菜品")
    public Result update(@RequestBody DishDTO dishDTO){
        log.info("修改菜品：{}", dishDTO);
        dishService.updateWithFlavor(dishDTO);
        cleanCache("dish_*");

        return Result.success();
    }

    @PostMapping("/status/{status}")
    @ApiOperation("菜品起售停售")
    public Result<String> startOrStop(@PathVariable Integer status, Long id){
        dishService.startOrStop(status, id);

        // 將所有dish_開頭的key全部清除
        cleanCache("dish_*");
        return Result.success();
    }

    @GetMapping("/list")
    @ApiOperation("根據分類id查詢菜品")
    public Result<List<Dish>> list(Long categoryId){
        log.info("根據分類id：{} 查詢菜品",categoryId);
        List<Dish> list = dishService.list(categoryId);
        return Result.success(list);
    }

    // 清理緩存數據
    private void cleanCache(String pattern) {
        // 將所有dish_開頭的key全部清除
        Set keys = redisTemplate.keys(pattern);
        redisTemplate.delete(keys);
    }
}

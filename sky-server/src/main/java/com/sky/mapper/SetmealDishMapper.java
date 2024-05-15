package com.sky.mapper;

import com.sky.entity.SetmealDish;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SetmealDishMapper {

    List<Long> getSetmelIdsByDishIds(List<Long> dishIds);

    void insertBatch(List<SetmealDish> setMealDishes);

    @Delete("DELETE from setmeal_dish where setmeal_id = #{setmealId}")
    void deleteBySetMealId(Long setMealId);
}

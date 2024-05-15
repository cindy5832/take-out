package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.SetMealService;
import com.sky.vo.SetmealVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SetMealServiceImpl implements SetMealService {
    @Autowired
    private SetmealDishMapper setmealDishMapper;

    @Autowired
    private SetmealMapper setmealMapper;

    @Autowired
    private DishMapper dishMapper;

    @Override
    // 新增套餐同時須保存套餐和菜品的關係
    @Transactional
    public void saveWithDish(SetmealDTO setmealDTO) {
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);
        // 向套餐插入數據
        setmealMapper.insert(setmeal);
        // 獲取生成的套餐Id
        Long setMealId = setmeal.getId();

        // 獲取生成的套餐id
        List<SetmealDish> setMealDishes = setmealDTO.getSetmealDishes();
        setMealDishes.forEach(dish ->{
            dish.setSetmealId(setMealId);
        });

        // 保存套餐和菜品的關聯關係
        setmealDishMapper.insertBatch(setMealDishes);
    }

    @Override
    public PageResult pageQuery(SetmealPageQueryDTO setmealPageQueryDTO) {
        PageHelper.startPage(setmealPageQueryDTO.getPage(), setmealPageQueryDTO.getPageSize());
        Page<SetmealVO> page = setmealMapper.pageQuery(setmealPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    // 批量刪除套餐
    @Override
    @Transactional
    public void deleteBatch(List<Long> ids) {
        ids.forEach(id ->{
            Setmeal setmeal = setmealMapper.getById(id);
            if(StatusConstant.ENABLE == setmeal.getStatus()){
                throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ON_SALE);
            }
        });
        ids.forEach(setMealId -> {
            setmealMapper.deleteById(setMealId);
            setmealDishMapper.deleteBySetMealId(setMealId);
        });
    }

    @Override
    // 根據id查詢套餐和關聯的菜品數據
    public SetmealVO getByIdWithDish(Long id) {
        Setmeal setmeal = setmealMapper.getById(id);
        List<SetmealDish> setmealDishes = setmealDishMapper.getBySetMealId(id);
        SetmealVO vo = new SetmealVO();
        BeanUtils.copyProperties(setmeal, vo);
        vo.setSetmealDishes(setmealDishes);
        return vo;
    }

    @Override
    // 修改套餐
    @Transactional
    public void update(SetmealDTO setmealDTO) {
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);
        // 修改套餐
        setmealMapper.update(setmeal);
        // 套餐id
        Long setMeadId = setmealDTO.getId();
        // 刪除套餐和菜品的關係關聯表
        setmealDishMapper.deleteBySetMealId(setMeadId);

        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        setmealDishes.forEach(setmealDish ->{
            setmealDish.setSetmealId(setMeadId);
        });
        setmealDishMapper.insertBatch(setmealDishes);
    }
}

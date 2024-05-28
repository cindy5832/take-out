package com.sky.service;

import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;

public interface UserService {
    // Wechat登入
    User wxLogin(UserLoginDTO userLoginDTO);
}

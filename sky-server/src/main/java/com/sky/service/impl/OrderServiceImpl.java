package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.HttpClientUtil;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Value("${sky.shop.address}")
    private String shopAddress;

    @Value("${sky.baidu.ak}")
    private String ak;

    private final String mapUrl = "https://api.map.baidu.com/geocoding/v3";

    private final String directionPlanUrl = "https://api.map.baidu.com/directionlite/v1/driving";

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private AddressBookMapper addressBookMapper;

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WeChatPayUtil weChatPayUtil;


    @Override
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        // 處理業務異常 (地址為空、購物車數據為空)
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        String address = addressBook.getCityName() + addressBook.getDistrictName() + addressBook.getDetail();
        checkOutOfRange(address);

        // 查詢當前購物車數據
        Long userId = BaseContext.getCurrentId();

        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList == null || shoppingCartList.size() == 0) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        // 向訂單表插入1條數據
        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, orders);
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setConsignee(addressBook.getConsignee());
        orders.setUserId(userId);

        orderMapper.insert(orders);

        List<OrderDetail> orderDetailList = new ArrayList<>();
        // 向訂單明細表插入n條數據
        for (ShoppingCart cart : shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail(); // 訂單明細
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setOrderId(orders.getId()); // 設置訂單明細關聯的訂單id
            orderDetailList.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetailList);

        // 清空當前購用戶購物車數據
        shoppingCartMapper.deleteByUserId(userId);

        // 封裝VO返回結果
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(orders.getId())
                .orderTime(orders.getOrderTime())
                .orderAmount(orders.getAmount())
                .orderNumber(orders.getNumber())
                .build();
        return orderSubmitVO;
    }

    private void checkOutOfRange(String address) {
        Map map = new HashMap();
        map.put("address", shopAddress);
        map.put("output", "json");
        map.put("ak", ak);
        // 取得店舖的經緯度座標
        String shopCoordinate = HttpClientUtil.doGet(mapUrl, map);
        JSONObject object = JSON.parseObject(shopCoordinate);
        if (!object.getString("status").equals("0")) {
            throw new OrderBusinessException("商店地址解析失敗");
        }
        // 數據解析
        JSONObject location = object.getJSONObject("result").getJSONObject("location");
        String lat = location.getString("lat");
        String lng = location.getString("lng");

        String shopLngLat = lat + "," + lng;
        log.info("商店地址：{}，解析座標為：{}", location, shopLngLat);

        map.put("address", address);
        String userCoordinate = HttpClientUtil.doGet(mapUrl, map);
        object = JSON.parseObject(userCoordinate);
        if (!object.getString("status").equals("0")) {
            throw new OrderBusinessException("收貨地址解析失敗");
        }

        location = object.getJSONObject("result").getJSONObject("location");
        lat = location.getString("lat");
        lng = location.getString("lng");

        String userLatLng = lat + "," + lng;
        log.info("收貨地址：{}，解析座標為：{}", location, userLatLng);

        map.put("origin", shopLngLat);
        map.put("destination", userLatLng);
        map.put("steps_info", "0");

        String json = HttpClientUtil.doGet(directionPlanUrl, map);
        object = JSON.parseObject(json);
        if (!object.getString("status").equals("0")) {
            throw new OrderBusinessException("配送路線規畫失敗");
        }
        JSONObject result = object.getJSONObject("result");
        JSONArray jsonArray = (JSONArray) result.get("route");
        Integer distance = (Integer) ((JSONObject) jsonArray.get(0)).get("distance");

        if (distance > 5000) {
            // 超出5000 m
            throw new OrderBusinessException("超出配送範圍");
        }
    }

    @Override
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 登入當前登入用戶id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        // 調用WeChat支付api，生成預支付交易單
        JSONObject jsonObject = weChatPayUtil.pay(
                ordersPaymentDTO.getOrderNumber(), // 商戶訂單號
                new BigDecimal(0.01), // 支付金額，單位=元
                "外送訂單", // 商品描述
                user.getOpenid() // WeChat 用戶的openid
        );

        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
            throw new OrderBusinessException("该订单已支付");
        }

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));

        return vo;
    }

    @Override
    public void paySuccess(String outTradeNo) {
        // 根據訂單號查詢訂單
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);

        // 根據訂單id更新訂單狀態、支付方式、支付狀態、結帳時間
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);
    }

    @Override
    public PageResult pageQuery(int page, int pageSize, Integer status) {
        // 設置分頁
        PageHelper.startPage(page, pageSize);

        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);

        // 分頁條件查詢
        Page<Orders> pages = orderMapper.pageQuery(ordersPageQueryDTO);
        List<OrderVO> list = new ArrayList<>();
        // 查出訂單明細，並封裝成OrderVO響應
        if (pages != null || pages.getTotal() > 0) {
            for (Orders orders : pages) {
                Long orderId = orders.getId(); // 訂單id
                List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orderId);

                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                orderVO.setOrderDetailList(orderDetailList);

                list.add(orderVO);
            }
        }
        return new PageResult(pages.getTotal(), list);
    }

    @Override
    public OrderVO details(Long id) {
        // 根據id 查詢訂單
        Orders orders = orderMapper.getById(id);
        // 查詢該訂單對應的菜品/套餐 明細
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        // 查詢訂單及其詳情封裝到OrderVO並返回
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailList);
        return orderVO;
    }

    @Override
    public void userCancelById(Long id) throws Exception {
        // 根據Id查詢訂單
        Orders orders = orderMapper.getById(id);
        // 確認訂單是否存在
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 訂單狀態 1待付款 2待接單 3已接單 4派送中 5已完成 6已取消
        if (orders.getStatus() > 2) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Orders order = new Orders();
        order.setId(orders.getId());

        // 訂單處於待接單狀態取消，需退款
        if (orders.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            // 呼叫微信支付退款接口
            weChatPayUtil.refund(
                    orders.getNumber(), // 商戶訂單號
                    orders.getNumber(), // 商戶退款單號
                    new BigDecimal(0.01), // 退款金額單位 元
                    new BigDecimal(0.01) // 原訂單金額
            );
            order.setPayStatus(Orders.REFUND);
        }
        // 更新訂單狀態、取消原因、取消時間
        order.setStatus(Orders.CANCELLED);
        order.setCancelReason("用戶取消");
        order.setCancelTime(LocalDateTime.now());
        orderMapper.update(order);
    }

    @Override
    public void repetition(Long id) {
        Long userId = BaseContext.getCurrentId();
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(userId);

        // 將訂單詳情對象轉換為購物車對象
        List<ShoppingCart> shoppingCartList = orderDetailList.stream().map(x -> {
            ShoppingCart shoppingCart = new ShoppingCart();

            // 將原訂單詳情裡面的菜色資訊重新複製到購物車物件中
            BeanUtils.copyProperties(x, shoppingCart, "id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());
            return shoppingCart;
        }).collect(Collectors.toList());

        // 將購物車物件批次加入資料庫
        shoppingCartMapper.insertBatch(shoppingCartList);
    }

    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());

        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);
        List<OrderVO> orderVOS = getOrderVOList(page);
        return new PageResult(page.getTotal(), orderVOS);
    }

    @Override
    public OrderStatisticsVO statistics() {
        // 根據狀態，分別查詢出待接單、待派送、派送中的訂單數量
        Integer toBeConfirmed = orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed = orderMapper.countStatus(Orders.CONFIRMED);
        Integer deliveryInProgress = orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);

        // 將查詢出的資料封裝到orderStatisticsVO中回應
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);

        return orderStatisticsVO;
    }

    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Orders orders = Orders.builder()
                .id(ordersConfirmDTO.getId())
                .status(ordersConfirmDTO.getStatus())
                .build();
        orderMapper.update(orders);
    }

    @Override
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception {
        // 根據id查詢訂單
        Orders orders = orderMapper.getById(ordersRejectionDTO.getId());
        // 訂單只有存在且狀態為2（待接單）才可以拒單
        if (orders == null || !orders.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        // 支付狀態
        Integer payStatus = orders.getPayStatus();
        if (payStatus == Orders.PAID) {
            String refund = weChatPayUtil.refund(
                    orders.getNumber(),
                    orders.getNumber(),
                    new BigDecimal(0.01),
                    new BigDecimal(0.01)
            );
            log.info("申請退款：{}", refund);
        }
        Orders order = new Orders();
        order.setId(orders.getId());
        order.setStatus(orders.getStatus());
        order.setRejectionReason(ordersRejectionDTO.getRejectionReason());
        order.setCancelTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    @Override
    public void cancel(OrdersCancelDTO ordersCancelDTO) throws Exception {
        Orders orders = orderMapper.getById(ordersCancelDTO.getId());
        Integer payStatus = orders.getPayStatus();
        if (payStatus == 1) {
            String refund = weChatPayUtil.refund(
                    orders.getNumber(),
                    orders.getNumber(),
                    new BigDecimal(0.01),
                    new BigDecimal(0.01)
            );
            log.info("申請退款：{}", refund);
        }

        // 管理端取消訂單需退款，依訂單id更新訂單狀態、取消原因、取消時間
        Orders orders1 = new Orders();
        orders1.setId(ordersCancelDTO.getId());
        orders1.setStatus(Orders.CANCELLED);
        orders1.setCancelReason(ordersCancelDTO.getCancelReason());
        orders1.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders1);
    }

    @Override
    public void delivery(Long id) {
        Orders orders = orderMapper.getById(id);

        // 校驗訂單是否存在，且狀態為3
        if (orders == null || !orders.getStatus().equals(Orders.CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders order = new Orders();
        order.setId(orders.getId());
        order.setStatus(Orders.DELIVERY_IN_PROGRESS);

        orderMapper.update(orders);
    }

    @Override
    public void complete(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null || !orders.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Orders order = new Orders();
        order.setId(orders.getId());
        order.setStatus(Orders.COMPLETED);
        order.setDeliveryTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    private List<OrderVO> getOrderVOList(Page<Orders> page) {
        List<OrderVO> orderVOList = new ArrayList<>();
        List<Orders> ordersList = page.getResult();
        if (!CollectionUtils.isEmpty(ordersList)) {
            for (Orders orders : ordersList) {
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                String orderDishes = getOrderDishesStr(orders);

                orderVO.setOrderDishes(orderDishes);
                ordersList.add(orderVO);
            }
        }
        return orderVOList;
    }

    private String getOrderDishesStr(Orders orders) {
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());
        // 將每個訂單菜餚資訊拼接為字串（格式：宮保雞丁*3；）
        List<String> orderDishList = orderDetailList.stream().map(x -> {
            String orderDish = x.getName() + "*" + x.getNumber() + ";";
            return orderDish;
        }).collect(Collectors.toList());
        // 將該訂單對應的所有菜餚資訊拼接在一起
        return String.join("", orderDishList);
    }
}

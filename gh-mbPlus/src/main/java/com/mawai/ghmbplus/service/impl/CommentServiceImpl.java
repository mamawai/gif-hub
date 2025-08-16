package com.mawai.ghmbplus.service.impl;

import com.mawai.ghmbplus.model.Comment;
import com.mawai.ghmbplus.dao.CommentMapper;
import com.mawai.ghmbplus.service.CommentService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 评论表 服务实现类
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Service
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements CommentService {

}

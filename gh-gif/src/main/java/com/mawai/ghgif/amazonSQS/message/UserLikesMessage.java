package com.mawai.ghgif.amazonSQS.message;

import com.mawai.ghmbplus.model.UserLike;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLikesMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    private String userId;
    List<UserLike> newLikes;
    List<UserLike> deleteLikes;
}

package com.feichaoyu.cas.dataobject;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * @author feichaoyu
 */
@Getter
@Setter
@Accessors(chain = true)
public class UserDO {
    private String id;

    private String username;

    private String password;
}

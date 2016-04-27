package com.chanceit.orm;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.Table;
import org.javalite.activejdbc.annotations.IdName;

@SuppressWarnings("serial")
@Table("Players")
@IdName("id")
public class Player extends Model {}

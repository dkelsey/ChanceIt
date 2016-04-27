package com.chanceit.orm;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.Table;
import org.javalite.activejdbc.annotations.IdName;

@SuppressWarnings("serial")
@Table("Games")
@IdName("id")
public class Game extends Model {}
